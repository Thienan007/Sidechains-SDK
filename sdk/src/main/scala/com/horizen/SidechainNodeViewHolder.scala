package com.horizen


import akka.actor.{ActorRef, ActorSystem, Props}
import com.horizen.block.SidechainBlock
import com.horizen.chain.FeePaymentsInfo
import com.horizen.consensus._
import com.horizen.node.SidechainNodeView
import com.horizen.params.NetworkParams
import com.horizen.proposition.{PublicKey25519Proposition, VrfPublicKey}
import com.horizen.state.ApplicationState
import com.horizen.storage._
import com.horizen.utils.BytesUtils
import com.horizen.validation._
import com.horizen.wallet.ApplicationWallet
import scorex.core.NodeViewHolder.DownloadRequest
import scorex.core.consensus.History.ProgressInfo
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages._
import scorex.core.settings.ScorexSettings
import scorex.core.utils.NetworkTimeProvider
import scorex.core.{bytesToVersion, idToVersion, versionToId}
import scorex.util.{ModifierId, ScorexLogging, bytesToId, idToBytes}

import java.util
import scala.annotation.tailrec
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

class SidechainNodeViewHolder(sidechainSettings: SidechainSettings,
                              historyStorage: SidechainHistoryStorage,
                              consensusDataStorage: ConsensusDataStorage,
                              stateStorage: SidechainStateStorage,
                              forgerBoxStorage: SidechainStateForgerBoxStorage,
                              utxoMerkleTreeStorage: SidechainStateUtxoMerkleTreeStorage,
                              walletBoxStorage: SidechainWalletBoxStorage,
                              secretStorage: SidechainSecretStorage,
                              walletTransactionStorage: SidechainWalletTransactionStorage,
                              forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
                              cswDataStorage: SidechainWalletCswDataStorage,
                              params: NetworkParams,
                              timeProvider: NetworkTimeProvider,
                              applicationWallet: ApplicationWallet,
                              applicationState: ApplicationState,
                              genesisBlock: SidechainBlock)
  extends scorex.core.NodeViewHolder[SidechainTypes#SCBT, SidechainBlock]
  with ScorexLogging
  with SidechainTypes
{
  override type SI = SidechainSyncInfo
  override type HIS = SidechainHistory
  override type MS = SidechainState
  override type VL = SidechainWallet
  override type MP = SidechainMemoryPool

  case class SidechainNodeUpdateInformation(history: HIS,
                                            state: MS,
                                            wallet: VL,
                                            failedMod: Option[SidechainBlock],
                                            alternativeProgressInfo: Option[ProgressInfo[SidechainBlock]],
                                            suffix: IndexedSeq[SidechainBlock])

  override val scorexSettings: ScorexSettings = sidechainSettings.scorexSettings

  lazy val listOfStorageInfo : Seq[SidechainStorageInfo] = Seq[SidechainStorageInfo](
    historyStorage, consensusDataStorage,
    utxoMerkleTreeStorage, stateStorage, forgerBoxStorage,
    secretStorage, walletBoxStorage, walletTransactionStorage, forgingBoxesInfoStorage, cswDataStorage)

  private def semanticBlockValidators(params: NetworkParams): Seq[SemanticBlockValidator] = Seq(new SidechainBlockSemanticValidator(params))
  private def historyBlockValidators(params: NetworkParams): Seq[HistoryBlockValidator] = Seq(
    new WithdrawalEpochValidator(params),
    new MainchainPoWValidator(params),
    new MainchainBlockReferenceValidator(params),
    new ConsensusValidator(timeProvider)
  )

  // this method is called at the startup after the load of the storages from the persistent db. It might happen that the node was not
  // stopped gracefully and therefore the consistency among storages might not be ensured. This method tries to recover this situation
  def checkAndRecoverStorages(restoredData:  Option[(SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool)]):
      Option[(SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool)] =
  {

    restoredData.flatMap {
      dataOpt => {
        dumpStorages

        log.info("Checking state consistency...")

        val restoredHistory = dataOpt._1
        val restoredState = dataOpt._2
        val restoredWallet = dataOpt._3
        val restoredMempool = dataOpt._4

        // best block id is updated in history storage as very last step
        val historyVersion = idToVersion(restoredHistory.bestBlockId)

        // get common version of the state storages, if necessary some rollback is applied internally
        // according to the update procedure sequence
        restoredState.ensureStorageConsistencyAfterRestore match {
          case Success(checkedState) => {
            val checkedStateVersion = checkedState.version

            log.debug(s"history bestBlockId = ${historyVersion}, stateVersion = ${checkedStateVersion}")

            val height_h = restoredHistory.blockInfoById(restoredHistory.bestBlockId).height
            val height_s = restoredHistory.blockInfoById(versionToId(checkedStateVersion)).height
            log.debug(s"history height = ${height_h}, state height = ${height_s}")

            if (historyVersion == checkedStateVersion) {
              log.info("state and history storages are consistent")

              // get common version of the wallet storages, that at this point must be consistent among them
              // since history and state are (according to the update procedure sequence: state --> wallet --> history)
              // if necessary a rollback is applied internally to the forging box info storage, because
              // it might have been updated upon consensus epoch switch even before the state
              restoredWallet.ensureStorageConsistencyAfterRestore match {
                case Success(checkedWallet) => {
                  val checkedWalletVersion = checkedWallet.version
                  log.info(s"walletVersion = ${checkedWalletVersion}")
                  if (historyVersion == checkedWalletVersion) {
                    // This is the successful case
                    log.info("state, history and wallet storages are consistent")
                    dumpStorages
                    Some(restoredHistory, checkedState, checkedWallet, restoredMempool)
                  }
                  else {
                    log.error("state and wallet storages are not consistent and could not be recovered")
                    // wallet and state are not consistent, while state and history are, this should never happen
                    // state --> wallet --> history
                    None
                  }
                }
                case Failure(e) => {
                  log.error("wallet storages are not consistent", e)
                  None
                }
              }
            } else {
              log.warn("Inconsistent state and history storages, trying to recover...")

              // this is the sequence of blocks starting from active chain up to input block, unless a None is returned in case of errors
              restoredHistory.chainBack(versionToId(checkedStateVersion), restoredHistory.storage.isInActiveChain, Int.MaxValue) match {
                case Some(nonChainSuffix) => {
                  log.info(s"sequence of blocks not in active chain (root included) = ${nonChainSuffix}")
                  val rollbackTo = nonChainSuffix.head
                  nonChainSuffix.tail.headOption.foreach( childBlock => {
                    log.debug(s"Child ${childBlock} is in history")
                    log.debug(s"Child info ${restoredHistory.blockInfoById(childBlock)}")
                  })

                  // since the update order is state --> wallet --> history
                  // we can rollback both state and wallet to current best block in history or the ancestor of state block in active chain (which might as well be the same)
                  log.warn(s"Inconsistent storage and history, rolling back state and wallets to history best block id = ${rollbackTo}")

                  val rolledBackWallet = restoredWallet.rollback(idToVersion(rollbackTo))
                  val rolledBackState = restoredState.rollbackTo(idToVersion(rollbackTo))

                  (rolledBackState, rolledBackWallet) match {
                    case (Success(s), Success(w)) =>
                      log.debug("State and wallet succesfully rolled back")
                      dumpStorages
                      Some((restoredHistory, s, w, restoredMempool))
                    case (Failure(e), _) =>
                      log.error("State roll back failed: ", e)
                      context.system.eventStream.publish(RollbackFailed)
                      None
                    case (_, Failure(e)) =>
                      log.error("Wallet roll back failed: ", e)
                      context.system.eventStream.publish(RollbackFailed)
                      None
                  }
                }
                case None => {
                  log.error("Could not recover storages inconsistency, could not find a rollback point in history")
                  None
                }
              }
            }
          }
          case Failure(ex) => {
            log.error("state storages are not consistent and could not be recovered", ex)
            None
          }
        }
      }
    }
  }

  override def restoreState(): Option[(HIS, MS, VL, MP)] = {
    log.info("Restoring persistent state from storage...")
    val restoredData = for {
      history <- SidechainHistory.restoreHistory(historyStorage, consensusDataStorage, params, semanticBlockValidators(params), historyBlockValidators(params))
      state <- SidechainState.restoreState(stateStorage, forgerBoxStorage, utxoMerkleTreeStorage, params, applicationState)
      wallet <- SidechainWallet.restoreWallet(sidechainSettings.wallet.seed.getBytes, walletBoxStorage, secretStorage,
        walletTransactionStorage, forgingBoxesInfoStorage, cswDataStorage, params, applicationWallet)
      pool <- Some(SidechainMemoryPool.emptyPool)
    } yield (history, state, wallet, pool)

    val result = checkAndRecoverStorages(restoredData)
    result
  }

  def dumpStorages: Unit =
    try {
      val m = getStorageVersions.map{ case(k, v) => {"%-36s".format(k) + ": " + v}}
      m.foreach(x => log.debug(s"${x}"))
      log.trace(s"    ForgingBoxesInfoStorage vers:    ${forgingBoxesInfoStorage.rollbackVersions.slice(0, 3)}")
    } catch {
      case e: Exception =>
        // can happen during unit test with mocked objects
        log.warn("Could not print debug info about storages: " + e.getMessage)
    }

  def getStorageVersions: Map[String, String] =
    listOfStorageInfo.map(x => {
      x.getClass.getSimpleName -> x.lastVersionId.map(value => BytesUtils.toHexString(value.data())).getOrElse("")
    }).toMap


  override def postStop(): Unit = {
    log.info("SidechainNodeViewHolder actor is stopping...")
    super.postStop()
  }

  override protected def genesisState: (HIS, MS, VL, MP) = {
    val result = for {
      state <- SidechainState.createGenesisState(stateStorage, forgerBoxStorage, utxoMerkleTreeStorage, params, applicationState, genesisBlock)

      (_: ModifierId, consensusEpochInfo: ConsensusEpochInfo) <- Success(state.getCurrentConsensusEpochInfo)
      withdrawalEpochNumber: Int <- Success(state.getWithdrawalEpochInfo.epoch)

      wallet <- SidechainWallet.createGenesisWallet(sidechainSettings.wallet.seed.getBytes, walletBoxStorage, secretStorage,
        walletTransactionStorage, forgingBoxesInfoStorage, cswDataStorage, params, applicationWallet,
        genesisBlock, withdrawalEpochNumber, consensusEpochInfo)

      history <- SidechainHistory.createGenesisHistory(historyStorage, consensusDataStorage, params, genesisBlock, semanticBlockValidators(params),
        historyBlockValidators(params), StakeConsensusEpochInfo(consensusEpochInfo.forgingStakeInfoTree.rootHash(), consensusEpochInfo.forgersStake))

      pool <- Success(SidechainMemoryPool.emptyPool)
    } yield (history, state, wallet, pool)

    result.get
  }

  protected def getCurrentSidechainNodeViewInfo: Receive = {
    case SidechainNodeViewHolder.ReceivableMessages.GetDataFromCurrentSidechainNodeView(f) => try {
      sender() ! f(new SidechainNodeView(history(), minimalState(), vault(), memoryPool(), minimalState().applicationState, vault().applicationWallet))
    }
    catch {
      case e: Exception => sender() ! akka.actor.Status.Failure(e)
    }
  }

  protected def applyFunctionOnNodeView: Receive = {
    case SidechainNodeViewHolder.ReceivableMessages.ApplyFunctionOnNodeView(function) => try {
      sender() ! function(new SidechainNodeView(history(), minimalState(), vault(), memoryPool(), minimalState().applicationState, vault().applicationWallet))
    }
    catch {
      case e: Exception => sender() ! akka.actor.Status.Failure(e)
    }
  }

  protected def applyBiFunctionOnNodeView[T, A]: Receive = {
    case SidechainNodeViewHolder.ReceivableMessages.ApplyBiFunctionOnNodeView(function, functionParameter) => try {
      sender() ! function(new SidechainNodeView(history(), minimalState(), vault(), memoryPool(), minimalState().applicationState, vault().applicationWallet), functionParameter)
    }
    catch {
      case e: Exception => sender() ! akka.actor.Status.Failure(e)
    }
  }

  protected def processLocallyGeneratedSecret: Receive = {
    case SidechainNodeViewHolder.ReceivableMessages.LocallyGeneratedSecret(secret) =>
      vault().addSecret(secret) match {
        case Success(newVault) =>
          updateNodeView(updatedVault = Some(newVault))
          sender() ! Success(Unit)
        case Failure(ex) =>
          sender() ! Failure(ex)
      }
  }

  override def receive: Receive = {
      applyFunctionOnNodeView orElse
      applyBiFunctionOnNodeView orElse
      getCurrentSidechainNodeViewInfo orElse
      processLocallyGeneratedSecret orElse
      super.receive
  }

  // This method is actually a copy-paste of parent NodeViewHolder.pmodModify method.
  // The difference is that modifiers are applied to the State and Wallet simultaneously.
  override protected def pmodModify(pmod: SidechainBlock): Unit = {
    if (!history().contains(pmod.id)) {
      context.system.eventStream.publish(StartingPersistentModifierApplication(pmod))

      log.info(s"Apply modifier ${pmod.encodedId} of type ${pmod.modifierTypeId} to nodeViewHolder")

      history().append(pmod) match {
        case Success((historyBeforeStUpdate, progressInfo)) =>
          log.debug(s"Going to apply modifications to the state: $progressInfo")
          context.system.eventStream.publish(SyntacticallySuccessfulModifier(pmod))
          context.system.eventStream.publish(NewOpenSurface(historyBeforeStUpdate.openSurfaceIds()))

          if (progressInfo.toApply.nonEmpty) {
            val (newHistory, newStateTry, newWallet, blocksApplied) =
              updateStateAndWallet(historyBeforeStUpdate, minimalState(), vault(), progressInfo, IndexedSeq())

            newStateTry match {
              case Success(newState) =>
                val newMemPool = updateMemPool(progressInfo.toRemove, blocksApplied, memoryPool(), newState)
                // Note: in parent NodeViewHolder.pmodModify wallet was updated here.

                log.info(s"Persistent modifier ${pmod.encodedId} applied successfully, now updating node view")
                updateNodeView(Some(newHistory), Some(newState), Some(newWallet), Some(newMemPool))


              case Failure(e) =>
                log.warn(s"Can`t apply persistent modifier (id: ${pmod.encodedId}, contents: $pmod) to minimal state", e)
                updateNodeView(updatedHistory = Some(newHistory))
                context.system.eventStream.publish(SemanticallyFailedModification(pmod, e))
            }
          } else {
            requestDownloads(progressInfo)
            updateNodeView(updatedHistory = Some(historyBeforeStUpdate))
          }
        case Failure(e) =>
          log.warn(s"Can`t apply persistent modifier (id: ${pmod.encodedId}, contents: $pmod) to history", e)
          context.system.eventStream.publish(SyntacticallyFailedModification(pmod, e))
      }
    } else {
      log.warn(s"Trying to apply modifier ${pmod.encodedId} that's already in history")
    }
  }

  // This method is actually a copy-paste of parent NodeViewHolder.requestDownloads method.
  protected def requestDownloads(pi: ProgressInfo[SidechainBlock]): Unit = {
    pi.toDownload.foreach { case (tid, id) =>
      context.system.eventStream.publish(DownloadRequest(tid, id))
    }
  }

  // This method is actually a copy-paste of parent NodeViewHolder.updateState method.
  // The difference is that State is updated together with Wallet.
  @tailrec
  private def updateStateAndWallet(history: HIS,
                          state: MS,
                          wallet: VL,
                          progressInfo: ProgressInfo[SidechainBlock],
                          suffixApplied: IndexedSeq[SidechainBlock]): (HIS, Try[MS], VL, Seq[SidechainBlock]) = {
    requestDownloads(progressInfo)

    // Do rollback if chain switch needed
    val (walletToApplyTry: Try[VL], stateToApplyTry: Try[MS], suffixTrimmed: IndexedSeq[SidechainBlock]) = if (progressInfo.chainSwitchingNeeded) {
      @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
      val branchingPoint = progressInfo.branchPoint.get //todo: .get
      if (state.version != branchingPoint) {
        log.debug(s"chain reorg needed, rolling back state and wallet to branching point: ${branchingPoint}")
        (
          wallet.rollback(idToVersion(branchingPoint)),
          state.rollbackTo(idToVersion(branchingPoint)),
          trimChainSuffix(suffixApplied, branchingPoint)
        )
      } else (Success(wallet), Success(state), IndexedSeq())
    } else (Success(wallet), Success(state), suffixApplied)

    (stateToApplyTry, walletToApplyTry) match {
      case (Success(stateToApply), Success(walletToApply)) => {
        log.debug("calling applyStateAndWallet")
        val nodeUpdateInfo = applyStateAndWallet(history, stateToApply, walletToApply, suffixTrimmed, progressInfo)

        nodeUpdateInfo.failedMod match {
          case Some(_) =>
            @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
            val alternativeProgressInfo = nodeUpdateInfo.alternativeProgressInfo.get
            updateStateAndWallet(nodeUpdateInfo.history, nodeUpdateInfo.state, nodeUpdateInfo.wallet, alternativeProgressInfo, nodeUpdateInfo.suffix)
          case None => (nodeUpdateInfo.history, Success(nodeUpdateInfo.state), nodeUpdateInfo.wallet, nodeUpdateInfo.suffix)
        }
      }
      case (Failure(e), _) =>
        log.error("State rollback failed: ", e)
        context.system.eventStream.publish(RollbackFailed)
        //todo: what to return here? the situation is totally wrong
        ???
      case (_, Failure(e)) =>
        log.error("Wallet rollback failed: ", e)
        context.system.eventStream.publish(RollbackFailed)
        //todo: what to return here? the situation is totally wrong
        ???
    }
  }

  // This method is actually a copy-paste of parent NodeViewHolder.trimChainSuffix method.
  protected def trimChainSuffix(suffix: IndexedSeq[SidechainBlock], rollbackPoint: scorex.util.ModifierId): IndexedSeq[SidechainBlock] = {
    val idx = suffix.indexWhere(_.id == rollbackPoint)
    if (idx == -1) IndexedSeq() else suffix.drop(idx)
  }

  // Apply state and wallet with blocks one by one, if consensus epoch is going to be changed -> notify wallet and history.
  protected def applyStateAndWallet(history: HIS,
                           stateToApply: MS,
                           walletToApply: VL,
                           suffixTrimmed: IndexedSeq[SidechainBlock],
                           progressInfo: ProgressInfo[SidechainBlock]): SidechainNodeUpdateInformation = {
    val updateInfoSample = SidechainNodeUpdateInformation(history, stateToApply, walletToApply, None, None, suffixTrimmed)
    progressInfo.toApply.foldLeft(updateInfoSample) { case (updateInfo, modToApply) =>
      if (updateInfo.failedMod.isEmpty) {
        // Check if the next modifier will change Consensus Epoch, so notify History and Wallet with current info.
        val (newHistory, newWallet) = if(updateInfo.state.isSwitchingConsensusEpoch(modToApply)) {
          log.debug("Switching consensus epoch")
          val (lastBlockInEpoch, consensusEpochInfo) = updateInfo.state.getCurrentConsensusEpochInfo
          val nonceConsensusEpochInfo = updateInfo.history.calculateNonceForEpoch(blockIdToEpochId(lastBlockInEpoch))
          val stakeConsensusEpochInfo = StakeConsensusEpochInfo(consensusEpochInfo.forgingStakeInfoTree.rootHash(), consensusEpochInfo.forgersStake)

          val historyAfterConsensusInfoApply =
            updateInfo.history.applyFullConsensusInfo(lastBlockInEpoch, FullConsensusEpochInfo(stakeConsensusEpochInfo, nonceConsensusEpochInfo))

          val walletAfterStakeConsensusApply = updateInfo.wallet.applyConsensusEpochInfo(consensusEpochInfo)
          (historyAfterConsensusInfoApply, walletAfterStakeConsensusApply)
        } else
          (updateInfo.history, updateInfo.wallet)

        // if a crash happens here the inconsistency between state and history wont appear: we should check the wallet storages and if a inconsistency is seen, rollback it
        // we have:
        //   1. state == history
        //   2. (wallet storages set) != state because of forgerBoxStorage
        //   3. history consensus storage has evolved as well but it has no rollback points

        //   At the restart all the update above would be re-applied, but in the meanwhile (before re-update it) such data might be used
        //   for instance in the forging phase or even in the validation phase.
        //   To rule out this possibility, even in case of future modifications,
        //   we can find a common root between state and ForgerBoxStorage versions and roll back up to that point

        updateInfo.state.applyModifier(modToApply) match {
          case Success(stateAfterApply) => {
            log.debug("success: modifier applied to state, blockInfo: " + newHistory.blockInfoById(modToApply.id))

            context.system.eventStream.publish(SemanticallySuccessfulModifier(modToApply))

            val stateWithdrawalEpochNumber: Int = stateAfterApply.getWithdrawalEpochInfo.epoch
            val (historyResult, walletResult) = if (stateAfterApply.isWithdrawalEpochLastIndex) {
              val feePayments = stateAfterApply.getFeePayments(stateWithdrawalEpochNumber)
              val historyAfterUpdateFee = newHistory.updateFeePaymentsInfo(modToApply.id, FeePaymentsInfo(feePayments))

              val walletAfterApply: SidechainWallet = newWallet.scanPersistent(modToApply, stateWithdrawalEpochNumber, feePayments, Some(stateAfterApply))
              (historyAfterUpdateFee, walletAfterApply)
            } else {
              val walletAfterApply: SidechainWallet = newWallet.scanPersistent(modToApply, stateWithdrawalEpochNumber, Seq(), None)
              (newHistory, walletAfterApply)
            }

            // as a final step update the history (validity and best block info), in this way we can check
            // at the startup the consistency of state and history storage versions and be sure that also intermediate steps
            // are consistent
            val historyAfterApply = historyResult.reportModifierIsValid(modToApply)
            log.debug("success: modifier applied to history, blockInfo " + historyAfterApply.blockInfoById(modToApply.id))

            SidechainNodeUpdateInformation(historyAfterApply, stateAfterApply, walletResult, None, None, updateInfo.suffix :+ modToApply)
          }
          case Failure(e) => {
            log.error(s"Could not apply modifier ${modToApply.id}, exception:" + e)
            val (historyAfterApply, newProgressInfo) = newHistory.reportModifierIsInvalid(modToApply, progressInfo)
            context.system.eventStream.publish(SemanticallyFailedModification(modToApply, e))
            SidechainNodeUpdateInformation(historyAfterApply, updateInfo.state, newWallet, Some(modToApply), Some(newProgressInfo), updateInfo.suffix)
          }
        }
      } else updateInfo
    }
  }
}

object SidechainNodeViewHolder /*extends ScorexLogging with ScorexEncoding*/ {
  object ReceivableMessages{
    case class GetDataFromCurrentSidechainNodeView[HIS, MS, VL, MP, A](f: SidechainNodeView => A)
    case class ApplyFunctionOnNodeView[HIS, MS, VL, MP, A](f: java.util.function.Function[SidechainNodeView, A])
    case class ApplyBiFunctionOnNodeView[HIS, MS, VL, MP, T, A](f: java.util.function.BiFunction[SidechainNodeView, T, A], functionParameter: T)
    case class LocallyGeneratedSecret[S <: SidechainTypes#SCS](secret: S)
  }
}

object SidechainNodeViewHolderRef {

  def props(sidechainSettings: SidechainSettings,
            historyStorage: SidechainHistoryStorage,
            consensusDataStorage: ConsensusDataStorage,
            stateStorage: SidechainStateStorage,
            forgerBoxStorage: SidechainStateForgerBoxStorage,
            utxoMerkleTreeStorage: SidechainStateUtxoMerkleTreeStorage,
            walletBoxStorage: SidechainWalletBoxStorage,
            secretStorage: SidechainSecretStorage,
            walletTransactionStorage: SidechainWalletTransactionStorage,
            forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
            cswDataStorage: SidechainWalletCswDataStorage,
            params: NetworkParams,
            timeProvider: NetworkTimeProvider,
            applicationWallet: ApplicationWallet,
            applicationState: ApplicationState,
            genesisBlock: SidechainBlock): Props =
    Props(new SidechainNodeViewHolder(sidechainSettings, historyStorage, consensusDataStorage, stateStorage, forgerBoxStorage, utxoMerkleTreeStorage, walletBoxStorage, secretStorage,
      walletTransactionStorage, forgingBoxesInfoStorage, cswDataStorage, params, timeProvider, applicationWallet, applicationState, genesisBlock))

  def apply(sidechainSettings: SidechainSettings,
            historyStorage: SidechainHistoryStorage,
            consensusDataStorage: ConsensusDataStorage,
            stateStorage: SidechainStateStorage,
            forgerBoxStorage: SidechainStateForgerBoxStorage,
            utxoMerkleTreeStorage: SidechainStateUtxoMerkleTreeStorage,
            walletBoxStorage: SidechainWalletBoxStorage,
            secretStorage: SidechainSecretStorage,
            walletTransactionStorage: SidechainWalletTransactionStorage,
            forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
            cswDataStorage: SidechainWalletCswDataStorage,
            params: NetworkParams,
            timeProvider: NetworkTimeProvider,
            applicationWallet: ApplicationWallet,
            applicationState: ApplicationState,
            genesisBlock: SidechainBlock)
           (implicit system: ActorSystem): ActorRef =
    system.actorOf(props(sidechainSettings, historyStorage, consensusDataStorage, stateStorage, forgerBoxStorage, utxoMerkleTreeStorage, walletBoxStorage, secretStorage,
      walletTransactionStorage, forgingBoxesInfoStorage, cswDataStorage, params, timeProvider, applicationWallet, applicationState, genesisBlock))

  def apply(name: String,
            sidechainSettings: SidechainSettings,
            historyStorage: SidechainHistoryStorage,
            consensusDataStorage: ConsensusDataStorage,
            stateStorage: SidechainStateStorage,
            forgerBoxStorage: SidechainStateForgerBoxStorage,
            utxoMerkleTreeStorage: SidechainStateUtxoMerkleTreeStorage,
            walletBoxStorage: SidechainWalletBoxStorage,
            secretStorage: SidechainSecretStorage,
            walletTransactionStorage: SidechainWalletTransactionStorage,
            forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
            cswDataStorage: SidechainWalletCswDataStorage,
            params: NetworkParams,
            timeProvider: NetworkTimeProvider,
            applicationWallet: ApplicationWallet,
            applicationState: ApplicationState,
            genesisBlock: SidechainBlock)
           (implicit system: ActorSystem): ActorRef =
    system.actorOf(props(sidechainSettings, historyStorage, consensusDataStorage, stateStorage, forgerBoxStorage, utxoMerkleTreeStorage, walletBoxStorage, secretStorage,
      walletTransactionStorage, forgingBoxesInfoStorage, cswDataStorage, params, timeProvider, applicationWallet, applicationState, genesisBlock), name)
}
