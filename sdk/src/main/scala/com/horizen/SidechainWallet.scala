package com.horizen

import java.{lang, util}
import java.util.{List => JList, Optional => JOptional}
import com.horizen.block.{MainchainBlockReferenceData, SidechainBlock}
import com.horizen.box.{Box, CoinsBox, ForgerBox, ZenBox}
import com.horizen.consensus.{ConsensusEpochInfo, ConsensusEpochNumber, ForgingStakeInfo}
import com.horizen.wallet.ApplicationWallet
import com.horizen.node.NodeWallet
import com.horizen.params.NetworkParams
import com.horizen.proposition.{Proposition, PublicKey25519Proposition}
import com.horizen.secret.Secret
import com.horizen.storage._
import com.horizen.transaction.Transaction
import com.horizen.transaction.mainchain.{ForwardTransfer, SidechainCreation}
import com.horizen.utils.{ByteArrayWrapper, BytesUtils, ForgingStakeMerklePathInfo}
import scorex.core.{VersionTag, bytesToVersion, idToVersion, versionToBytes, versionToId}
import com.horizen.utils._
import scorex.util.{ModifierId, ScorexLogging}

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps


trait Wallet[S <: Secret, P <: Proposition, TX <: Transaction, PMOD <: scorex.core.PersistentNodeViewModifier, W <: Wallet[S, P, TX, PMOD, W]]
  extends scorex.core.transaction.wallet.Vault[TX, PMOD, W] {
  self: W =>

  def addSecret(secret: S): Try[W]

  def removeSecret(publicImage: P): Try[W]

  def secret(publicImage: P): Option[S]

  def secrets(): Set[S]

  def boxes(): Seq[WalletBox]

  def publicKeys(): Set[P]
}


class SidechainWallet private[horizen] (seed: Array[Byte],
                                        walletBoxStorage: SidechainWalletBoxStorage,
                                        secretStorage: SidechainSecretStorage,
                                        walletTransactionStorage: SidechainWalletTransactionStorage,
                                        forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
                                        cswDataStorage: SidechainWalletCswDataStorage,
                                        params: NetworkParams,
                                        val version: VersionTag,
                                        val applicationWallet: ApplicationWallet)
  extends Wallet[SidechainTypes#SCS,
                 SidechainTypes#SCP,
                 SidechainTypes#SCBT,
                 SidechainBlock,
                 SidechainWallet]
  with SidechainTypes
  with NodeWallet
  with ScorexLogging {
  override type NVCT = SidechainWallet

  require(applicationWallet != null, "ApplicationWallet must be NOT NULL.")

  // 1) check for existence
  // 2) try to store in SecretStore using SidechainSecretsCompanion
  override def addSecret(secret: SidechainTypes#SCS): Try[SidechainWallet] = Try {
    require(secret != null, "Secret must be NOT NULL.")
    secretStorage.add(secret).get
    applicationWallet.onAddSecret(secret)
    this
  }

  // 1) check for existence
  // 2) remove from SecretStore (note: provide a unique version to SecretStore)
  override def removeSecret(publicImage: SidechainTypes#SCP): Try[SidechainWallet] = Try {
    require(publicImage != null, "PublicImage must be NOT NULL.")
    secretStorage.remove(publicImage).get
    applicationWallet.onRemoveSecret(publicImage)
    this
  }

  override def secret(publicImage: SidechainTypes#SCP): Option[SidechainTypes#SCS] = {
    secretStorage.get(publicImage)
  }

  override def secrets(): Set[SidechainTypes#SCS] = {
    secretStorage.getAll.toSet
  }

  override def boxes(): Seq[WalletBox] = {
    walletBoxStorage.getAll
  }

  override def publicKeys(): Set[SidechainTypes#SCP] = {
    secretStorage.getAll.map(_.publicImage()).toSet
  }

  // just do nothing, we don't need to care about offchain objects inside the wallet
  override def scanOffchain(tx: SidechainTypes#SCBT): SidechainWallet = this

  // just do nothing, we don't need to care about offchain objects inside the wallet
  override def scanOffchain(txs: Seq[SidechainTypes#SCBT]): SidechainWallet = this

  @Deprecated
  override def scanPersistent(modifier: SidechainBlock): SidechainWallet = {
    throw new UnsupportedOperationException()
  }

  // Scan the modifier and:
  // 1) store wallet related data from it + some fee payments according to consensus rules;
  // 2) update CSW utxo metadata for all coin boxes in the end of WithdrawalEpoch (if utxoMerkleTreeViewOpt is defined)
  // 3) update CSW FT metadata for all FT related to current wallet.
  def scanPersistent(modifier: SidechainBlock,
                     withdrawalEpoch: Int,
                     feePaymentBoxes: Seq[ZenBox],
                     utxoMerkleTreeViewOpt: Option[UtxoMerkleTreeView]): SidechainWallet = {
    //require(modifier != null, "SidechainBlock must be NOT NULL.")
    val version = BytesUtils.fromHexString(modifier.id)
    val changes = SidechainState.changes(modifier).get
    val pubKeys = publicKeys()

    val txBoxes: Map[ByteArrayWrapper, SidechainTypes#SCBT] = modifier.transactions
      .foldLeft(Map.empty[ByteArrayWrapper, SidechainTypes#SCBT]) {
        (accMap, tx) =>
          accMap ++ tx.boxIdsToOpen().asScala.map(boxId => boxId -> tx) ++
            tx.newBoxes().asScala.map(b => new ByteArrayWrapper(b.id()) -> tx)
      }

    val newBoxes: Seq[SidechainTypes#SCB] = changes.toAppend.map(_.box) ++ feePaymentBoxes.map(_.asInstanceOf[SidechainTypes#SCB])

    val newWalletBoxes = newBoxes
      .withFilter(box => pubKeys.contains(box.proposition()))
      .map(box => {
        if (txBoxes.contains(box.id())) {
          val boxTransaction = txBoxes(box.id())
          new WalletBox(box, ModifierId @@ boxTransaction.id, modifier.timestamp)
        } else { // For fee payment boxes which have no containing transaction.
          new WalletBox(box, modifier.timestamp)
        }
      })

    val newDelegatedForgerBoxes: Seq[ForgerBox] = newBoxes.withFilter(_.isInstanceOf[ForgerBox]).map(_.asInstanceOf[ForgerBox])
      .filter(forgerBox => pubKeys.contains(forgerBox.blockSignProposition()))

    val boxIdsToRemove = changes.toRemove.map(_.boxId.array)

    try {
      log.debug("calling applicationWallet.onChangeBoxes")
      applicationWallet.onChangeBoxes(version, newBoxes.toList.asJava, boxIdsToRemove.toList.asJava)

      // Note: the fee payment boxes have no related transactions
      val transactions: Seq[SidechainTypes#SCBT] = (newWalletBoxes.map(_.box.id()) ++ boxIdsToRemove).flatMap(id => txBoxes.get(id)).distinct

      walletBoxStorage.update(new ByteArrayWrapper(version), newWalletBoxes.toList, boxIdsToRemove.toList).get

      walletTransactionStorage.update(new ByteArrayWrapper(version), transactions).get

      // We keep forger boxes separate to manage forging stake delegation
      forgingBoxesInfoStorage.updateForgerBoxes(new ByteArrayWrapper(version), newDelegatedForgerBoxes, boxIdsToRemove).get

      // In case utxoMerkleTreeViewOpt is defined, calculate and store the CSW data for every coin box in the Wallet
      val utxoCswData: Seq[CswData] = utxoMerkleTreeViewOpt.map(view => calculateUtxoCswData(view)).getOrElse(Seq())
      val ftCswData = calculateForwardTransferCswData(modifier.mainchainBlockReferencesData, pubKeys)

      cswDataStorage.update(new ByteArrayWrapper(version), withdrawalEpoch, ftCswData ++ utxoCswData)
    } catch {
      case e: Exception =>
        log.error("Could not update application wallet and storages: " + e.getMessage)
        throw e
    }

    this
  }

  private[horizen] def calculateUtxoCswData(view: UtxoMerkleTreeView): Seq[CswData] = {
    boxes().filter(wb => wb.box.isInstanceOf[CoinsBox[_ <: PublicKey25519Proposition]]).map(wb => {
      val box = wb.box
      UtxoCswData(box.id(), box.proposition().bytes, box.value(), box.nonce(),
        box.customFieldsHash(), view.utxoMerklePath(box.id()).get)
    })
  }

  private[horizen] def calculateForwardTransferCswData(mcBlockRefDataSeq: Seq[MainchainBlockReferenceData], pubKeys: Set[SidechainTypes#SCP]): Seq[CswData] = {
    val ftCswDataList = ListBuffer[CswData]()

    mcBlockRefDataSeq.foreach(mcBlockRefData => {
      // If MC2SCAggTx is present -> collect wallet related FTs
      mcBlockRefData.sidechainRelatedAggregatedTransaction.foreach(aggTx => {
        var ftLeafIdx: Int = -1
        val walletFTs: Seq[(ForwardTransfer, Int)] = aggTx.mc2scTransactionsOutputs().asScala.flatMap(_ match {
          case _: SidechainCreation => None // No CSW support for ScCreation outputs as FT
          case ft: ForwardTransfer =>
            ftLeafIdx += 1
            if (pubKeys.contains(ft.getBox.proposition()))
              Some((ft, ftLeafIdx))
            else
              None
        })

        if(walletFTs.nonEmpty) {
          val commitmentTree = mcBlockRefData.commitmentTree(params.sidechainId, params.sidechainCreationVersion)
          val scCommitmentMerklePath = commitmentTree.getSidechainCommitmentMerklePath(params.sidechainId).get
          val btrCommitment = commitmentTree.getBtrCommitment(params.sidechainId).get
          val certCommitment = commitmentTree.getCertCommitment(params.sidechainId).get
          val scCrCommitment = commitmentTree.getScCrCommitment(params.sidechainId).get

          for ((ft: ForwardTransfer, leafIdx: Int) <- walletFTs) {
            val ftMerklePath = commitmentTree.getForwardTransferMerklePath(params.sidechainId, leafIdx).get
            ftCswDataList.append(
              ForwardTransferCswData(ft.getBox.id(), ft.getFtOutput.amount, ft.getFtOutput.propositionBytes,
                ft.getFtOutput.mcReturnAddress, ft.transactionHash(), ft.transactionIndex(), scCommitmentMerklePath,
                btrCommitment, certCommitment, scCrCommitment, ftMerklePath)
            )
          }
          commitmentTree.free()
        }
      })
    })

    ftCswDataList
  }

  // rollback BoxStorage and TransactionsStorage only. SecretStorage must not change.
  override def rollback(to: VersionTag): Try[SidechainWallet] = Try {
    log.debug(s"rolling back wallet to version = ${to}")
    require(to != null, "Version to rollback to must be NOT NULL.")
    val version = new ByteArrayWrapper(BytesUtils.fromHexString(to))
    // reverse order of update
    cswDataStorage.rollback(version).get
    forgingBoxesInfoStorage.rollback(version).get
    walletTransactionStorage.rollback(version).get
    walletBoxStorage.rollback(version).get
    applicationWallet.onRollback(version.data)

    new SidechainWallet(seed, walletBoxStorage, secretStorage, walletTransactionStorage,
      forgingBoxesInfoStorage, cswDataStorage, params, to, applicationWallet)
  }

  // Java NodeWallet interface definition
  override def allBoxes: JList[Box[Proposition]] = {
    walletBoxStorage.getAll.map(_.box).asJava
  }

  override def allBoxes(boxIdsToExclude: JList[Array[Byte]]): JList[Box[Proposition]] = {
    walletBoxStorage.getAll
      .filter((wb: WalletBox) => !BytesUtils.contains(boxIdsToExclude, wb.box.id()))
      .map(_.box)
      .asJava
  }

  override def boxesOfType(boxType: Class[_ <: Box[_ <: Proposition]]): JList[Box[Proposition]] = {
    walletBoxStorage.getByType(boxType)
      .map(_.box)
      .asJava
  }

  override def boxesOfType(boxType: Class[_ <: Box[_ <: Proposition]], boxIdsToExclude: JList[Array[Byte]]): JList[Box[Proposition]] = {
    walletBoxStorage.getByType(boxType)
      .filter((wb: WalletBox) => !BytesUtils.contains(boxIdsToExclude, wb.box.id()))
      .map(_.box)
      .asJava
  }

  override def boxesBalance(boxType: Class[_ <: Box[_ <: Proposition]]): java.lang.Long = {
    walletBoxStorage.getBoxesBalance(boxType)
  }

  override def secretByPublicKey(publicKey: Proposition): JOptional[Secret] = {
    secretStorage.get(publicKey) match {
      case Some(secret) => JOptional.of(secret)
      case None => JOptional.empty()
    }
  }

  override def allSecrets(): JList[Secret] = {
    secretStorage.getAll.asJava
  }

  override def secretsOfType(secretType: Class[_ <: Secret]): JList[Secret] = {
    secretStorage.getAll.filter(_.getClass.equals(secretType)).asJava
  }

  override def allCoinsBoxesBalance(): lang.Long = {
    walletBoxStorage.getAll.withFilter(_.box.isInstanceOf[CoinsBox[_ <: PublicKey25519Proposition]]).map(_.box.value()).sum
  }

  override def walletSeed(): Array[Byte] = seed

  def applyConsensusEpochInfo(epochInfo: ConsensusEpochInfo): SidechainWallet = {
    val merkleTreeLeaves = epochInfo.forgingStakeInfoTree.leaves().asScala.map(leaf => new ByteArrayWrapper(leaf))

    // Calculate merkle path for all delegated forgerBoxes
    val forgingStakeMerklePathInfoSeq: Seq[ForgingStakeMerklePathInfo] =
      ForgingStakeInfo.fromForgerBoxes(forgingBoxesInfoStorage.getForgerBoxes.getOrElse(Seq())).flatMap(forgingStakeInfo => {
        merkleTreeLeaves.indexOf(new ByteArrayWrapper(forgingStakeInfo.hash)) match {
          case -1 => None // May occur in case if Wallet doesn't contain information about all boxes for given blockSignKey and vrfKey
          case index => Some(ForgingStakeMerklePathInfo(
            forgingStakeInfo,
            epochInfo.forgingStakeInfoTree.getMerklePathForLeaf(index)
          ))
        }
      })

    forgingBoxesInfoStorage.updateForgingStakeMerklePathInfo(epochInfo.epoch, forgingStakeMerklePathInfoSeq).get
    this
  }

  def getForgingStakeMerklePathInfoOpt(requestedEpoch: ConsensusEpochNumber): Option[Seq[ForgingStakeMerklePathInfo]] = {
    // For given epoch N we should get data from the ending of the epoch N-2.
    // genesis block is the single and the last block of epoch 1 - that is a special case:
    // Data from epoch 1 is also valid for epoch 2, so for epoch N==2, we should get info from epoch 1.
    val storedConsensusEpochNumber: ConsensusEpochNumber = requestedEpoch match {
      case epoch if (epoch <= 2) => ConsensusEpochNumber @@ 1
      case epoch => ConsensusEpochNumber @@ (epoch - 2)
    }

    forgingBoxesInfoStorage.getForgingStakeMerklePathInfoForEpoch(storedConsensusEpochNumber)
  }

  def getCswData(withdrawalEpochNumber: Int): Seq[CswData] = {
    cswDataStorage.getCswData(withdrawalEpochNumber)
  }

  // Check that all wallet storages are consistent and in case forging box info storage is not, then try a rollback for it.
  // Return the state and common version or throw an exception if some unrecoverable misalignment has been detected
  def ensureStorageConsistencyAfterRestore: Try[SidechainWallet] = Try {
    // TODO csw capability will be optional, related storage might even be empty

    // It is assumed that when this method is called, all the wallet versions must be consistent among them
    // since history and state are (according to the update procedure sequence: state --> wallet --> history)
    // The only exception can be the forging box info storage, because, it might have been updated upon
    // consensus epoch switch even before the state update.
    // In that case it can be ahead by one version only (except in the genesis phase), and that is checked
    // before applying a rollback
    val versionBytes = walletBoxStorage.lastVersionId.get.data()
    val appWalletStateOk = applicationWallet.checkStoragesVersion(versionBytes)
    val version = bytesToVersion(versionBytes)
    if (
      // check these storages first, they are updated always together
        version == bytesToVersion(walletTransactionStorage.lastVersionId.get.data()) &&
          version == bytesToVersion(cswDataStorage.lastVersionId.get.data()) &&
          appWalletStateOk
    ) {
      // check forger box info storage, which is updated before state in case of epoch switch
      if (version == bytesToVersion(forgingBoxesInfoStorage.lastVersionId.get.data())) {
        log.debug("All wallet storage versions are consistent")
        this
      } else {
        val versionBaw = new ByteArrayWrapper(versionToBytes(version))
        // the version should be the previous at most
        val maxNumberOfVersionToRetrieve = 2
        val rollbackList = forgingBoxesInfoStorage.rollbackVersions(maxNumberOfVersionToRetrieve)
        if (rollbackList.last == versionBaw) {
          if (forgingBoxesInfoStorage.numberOfVersions == maxNumberOfVersionToRetrieve) {
            // this is ok, we have just the genesis block whose modId is the very first version, and the second
            // is the non-rollback version used when updating the ForgingStakeMerklePathInfo at the startup
            log.debug("All wallet storage versions are consistent")
            this
          } else {
            // it can be the case of process stopped when we had not yet updated the state and we are switching epoch
            log.warn(s"Wallet forger box storage versions is NOT consistent, trying to rollback")
            val rbVersion = forgingBoxesInfoStorage.rollback(versionBaw) match {
              case Success(_) => Some(bytesToVersion(versionBaw.data()))
              case Failure(_) => None
            }
            if (rbVersion.isEmpty) {
              // unrecoverable
              log.error("Could not recover wallet forging storages")
              throw new RuntimeException("Could not rollback wallet forging box info storages")
            }
            this
          }
        } else {
          log.error("Forging boxes info storage does not have the expected version in the rollback list")
          throw new RuntimeException("Forging boxes info storage does not have the expected version in the rollback list")
        }
      }
    } else {
      log.error("Wallet storage versions are NOT consistent")
      throw new RuntimeException("Wallet storage versions are NOT consistent")
    }
  }
}

object SidechainWallet
{
  private[horizen] def restoreWallet(seed: Array[Byte],
                                     walletBoxStorage: SidechainWalletBoxStorage,
                                     secretStorage: SidechainSecretStorage,
                                     walletTransactionStorage: SidechainWalletTransactionStorage,
                                     forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
                                     cswDataStorage: SidechainWalletCswDataStorage,
                                     params: NetworkParams,
                                     applicationWallet: ApplicationWallet) : Option[SidechainWallet] = {

    if (!walletBoxStorage.isEmpty) {
      val version = bytesToVersion(walletBoxStorage.lastVersionId.get)
      Some(new SidechainWallet(seed, walletBoxStorage, secretStorage, walletTransactionStorage,
        forgingBoxesInfoStorage, cswDataStorage, params, version, applicationWallet))
    } else
      None
  }

  private[horizen] def createGenesisWallet(seed: Array[Byte],
                                           walletBoxStorage: SidechainWalletBoxStorage,
                                           secretStorage: SidechainSecretStorage,
                                           walletTransactionStorage: SidechainWalletTransactionStorage,
                                           forgingBoxesInfoStorage: ForgingBoxesInfoStorage,
                                           cswDataStorage: SidechainWalletCswDataStorage,
                                           params: NetworkParams,
                                           applicationWallet: ApplicationWallet,
                                           genesisBlock: SidechainBlock,
                                           withdrawalEpochNumber: Int,
                                           consensusEpochInfo: ConsensusEpochInfo
                                    ) : Try[SidechainWallet] = Try {

    if (walletBoxStorage.isEmpty) {
      val genesisWallet = new SidechainWallet(seed, walletBoxStorage, secretStorage, walletTransactionStorage,
        forgingBoxesInfoStorage, cswDataStorage, params, idToVersion(genesisBlock.parentId), applicationWallet)
      genesisWallet.scanPersistent(genesisBlock, withdrawalEpochNumber, Seq(), None).applyConsensusEpochInfo(consensusEpochInfo)
    }
    else
      throw new RuntimeException("WalletBox storage is not empty!")
  }
}
