package com.horizen.api.http

import java.time.Instant
import java.util
import java.util.{Optional, ArrayList => JArrayList, List => JList}
import com.horizen.block.{MainchainBlockReference, SidechainBlock}
import com.horizen.box.data.{BoxData, ZenBoxData}
import com.horizen.box.{Box, ZenBox}
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.fixtures.{BoxFixture, CompanionsFixture, ForgerBoxFixture, MerkleTreeFixture, VrfGenerator}
import com.horizen.node.util.MainchainBlockReferenceInfo
import com.horizen.node.{NodeHistory, NodeMemoryPool, NodeState, NodeWallet, SidechainNodeView}
import com.horizen.params.MainNetParams
import com.horizen.proposition.Proposition
import com.horizen.secret.{PrivateKey25519, PrivateKey25519Creator}
import com.horizen.state.ApplicationState
import com.horizen.transaction.RegularTransaction
import com.horizen.utils.{BytesUtils, Pair, TestSidechainsVersionsManager}
import com.horizen.wallet.ApplicationWallet
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatestplus.mockito.MockitoSugar
import scorex.util.{ModifierId, bytesToId, idToBytes}

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{Failure, Success, Try}

class SidechainNodeViewUtilMocks extends MockitoSugar with BoxFixture with CompanionsFixture {

  val sidechainTransactionsCompanion: SidechainTransactionsCompanion = getDefaultTransactionsCompanion

  val mainchainBlockReferenceInfoRef = new MainchainBlockReferenceInfo(
    BytesUtils.fromHexString("0000000011aec26c29306d608645a644a592e44add2988a9d156721423e714e0"),
    BytesUtils.fromHexString("00000000106843ee0119c6db92e38e8655452fd85f638f6640475e8c6a3a3582"),
    230,
    BytesUtils.fromHexString("69c4f36c2b3f546aa57fa03c4df51923e17e8ea59ecfdea7f49c8aff06ec8208"),
    BytesUtils.fromHexString("69c4f36c2b3f546aa57fa03c4df51923e17e8ea59ecfdea7f49c8aff06ec8208")) // TO DO: check, probably use different sc id

  val secret1 = PrivateKey25519Creator.getInstance().generateSecret("testSeed1".getBytes())
  val secret2 = PrivateKey25519Creator.getInstance().generateSecret("testSeed2".getBytes())
  val secret3 = PrivateKey25519Creator.getInstance().generateSecret("testSeed3".getBytes())
  val secret4 = PrivateKey25519Creator.getInstance().generateSecret("testSeed4".getBytes())
  val box_1 = getZenBox(secret1.publicImage(), 1, 10)
  val box_2 = getZenBox(secret2.publicImage(), 1, 20)
  val box_3 = getZenBox(secret3.publicImage(), 1, 30)
  val box_4 = getForgerBox(secret4.publicImage(), 2, 30, secret4.publicImage(), getVRFPublicKey(4L))
  val box_5 = getCustomBox

  val allBoxes: util.List[Box[Proposition]] = walletAllBoxes()
  val transactionList: util.List[RegularTransaction] = getTransactionList

  val (forgingBox, forgerBoxMetadata) = ForgerBoxFixture.generateForgerBox(234)
  val genesisBlock: SidechainBlock = SidechainBlock.create(
    bytesToId(new Array[Byte](32)),
    SidechainBlock.BLOCK_VERSION,
    Instant.now.getEpochSecond - 10000,
    Seq(),
    Seq(),
    Seq(),
    Seq(),
    forgerBoxMetadata.blockSignSecret,
    forgerBoxMetadata.forgingStakeInfo,
    VrfGenerator.generateProof(456L),
    MerkleTreeFixture.generateRandomMerklePath(456L),
    new Array[Byte](32),
    sidechainTransactionsCompanion).get

  def getNodeHistoryMock(sidechainApiMockConfiguration: SidechainApiMockConfiguration): NodeHistory = {
    val history: NodeHistory = mock[NodeHistory]

    Mockito.when(history.getBlockById(ArgumentMatchers.any[String])).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getBlockById_return_value()) Optional.of(genesisBlock)
      else Optional.empty())

    Mockito.when(history.getLastBlockIds(ArgumentMatchers.any())).thenAnswer(_ => {
      val ids = new util.ArrayList[String]()
      ids.add("block_id_1")
      ids.add("block_id_2")
      ids.add("block_id_3")
      ids
    })

    Mockito.when(history.getBestBlock).thenAnswer(_ => genesisBlock)

    Mockito.when(history.getBlockHeightById(ArgumentMatchers.any[String])).thenAnswer(_ =>Optional.of(100))

    Mockito.when(history.getBlockIdByHeight(ArgumentMatchers.any())).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getBlockIdByHeight_return_value()) Optional.of("the_block_id")
      else Optional.empty())

    Mockito.when(history.getCurrentHeight).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getCurrentHeight_return_value()) 230
      else 0)

    Mockito.when(history.getBestMainchainBlockReferenceInfo).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getBestMainchainBlockReferenceInfo_return_value())
        Optional.of(mainchainBlockReferenceInfoRef)
      else Optional.empty())

    Mockito.when(history.getMainchainBlockReferenceInfoByMainchainBlockHeight(ArgumentMatchers.any())).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getMainchainBlockReferenceInfoByMainchainBlockHeight_return_value())
        Optional.of(mainchainBlockReferenceInfoRef)
      else Optional.empty())

    Mockito.when(history.getMainchainBlockReferenceInfoByHash(ArgumentMatchers.any())).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getMainchainBlockReferenceInfoByHash_return_value())
        Optional.of(mainchainBlockReferenceInfoRef)
      else Optional.empty())

    Mockito.when(history.getMainchainBlockReferenceByHash(ArgumentMatchers.any())).thenAnswer(_ =>
      if (sidechainApiMockConfiguration.getShould_history_getMainchainBlockReferenceByHash_return_value()) {
        val mcBlockHex = Source.fromResource("mcblock473173_mainnet").getLines().next()
        val mcBlockBytes = BytesUtils.fromHexString(mcBlockHex)
        MainchainBlockReference.create(mcBlockBytes, MainNetParams(), TestSidechainsVersionsManager()) match {
          case Success(ref) => Optional.of(ref)
          case Failure(exception) => Optional.empty()
        }
      }
      else Optional.empty())

    Mockito.when(history.searchTransactionInsideBlockchain(ArgumentMatchers.any[String])).thenAnswer(asw => {
      if (sidechainApiMockConfiguration.getShould_history_searchTransactionInBlockchain_return_value()) {
        val id = asw.getArgument(0).asInstanceOf[String]
        Optional.ofNullable(Try(transactionList.asScala.filter(tx => BytesUtils.toHexString(idToBytes(ModifierId @@ tx.id)).equalsIgnoreCase(id)).head).getOrElse(null))
      } else
        Optional.empty()
    })

    Mockito.when(history.searchTransactionInsideSidechainBlock(ArgumentMatchers.any[String], ArgumentMatchers.any[String])).thenAnswer(asw => {
      if (sidechainApiMockConfiguration.getShould_history_searchTransactionInBlock_return_value()) {
        val id = asw.getArgument(0).asInstanceOf[String]
        Optional.ofNullable(Try(transactionList.asScala.filter(tx => BytesUtils.toHexString(idToBytes(ModifierId @@ tx.id)).equalsIgnoreCase(id)).head).getOrElse(null))
      } else
        Optional.empty()
    })

    history
  }

  def getNodeStateMock(sidechainApiMockConfiguration: SidechainApiMockConfiguration): NodeState = {
    mock[NodeState]
  }

  private def walletAllBoxes(): util.List[Box[Proposition]] = {
    val list: util.List[Box[Proposition]] = new util.ArrayList[Box[Proposition]]()
    list.add(box_1.asInstanceOf[Box[Proposition]])
    list.add(box_2.asInstanceOf[Box[Proposition]])
    list.add(box_3.asInstanceOf[Box[Proposition]])
    list.add(box_4.asInstanceOf[Box[Proposition]])
    list.add(box_5.asInstanceOf[Box[Proposition]])
    list
  }

  def getNodeWalletMock(sidechainApiMockConfiguration: SidechainApiMockConfiguration): NodeWallet = {
    val wallet: NodeWallet = mock[NodeWallet]
    Mockito.when(wallet.boxesBalance(ArgumentMatchers.any())).thenAnswer(_ => Long.box(1000))
    Mockito.when(wallet.allCoinsBoxesBalance()).thenAnswer(_ => Long.box(5500))

    Mockito.when(wallet.allBoxes()).thenAnswer(_ => allBoxes)
    Mockito.when(wallet.allBoxes(ArgumentMatchers.any[util.List[Array[Byte]]])).thenAnswer(asw => {
      val args = asw.getArguments
      if (args != null && args.nonEmpty) {
        val arg = asw.getArgument(0).asInstanceOf[util.List[Array[Byte]]]
        if (arg.size() > 0)
          allBoxes.asScala.toList.filter(box => !BytesUtils.contains(arg, box.id())).asJava
        else allBoxes
      }
      else
        allBoxes
    })

    val listOfSecrets = List(secret1, secret2)

    Mockito.when(wallet.secretsOfType(ArgumentMatchers.any())).thenAnswer(_ => listOfSecrets.asJava)

    Mockito.when(wallet.walletSeed()).thenAnswer(_ => "a seed".getBytes)

    Mockito.when(wallet.allSecrets()).thenAnswer(_ => listOfSecrets.asJava)

    Mockito.when(wallet.secretByPublicKey(ArgumentMatchers.any[Proposition])).thenAnswer(asw => {
      val prop: Proposition = asw.getArgument(0).asInstanceOf[Proposition]
      if(BytesUtils.toHexString(prop.bytes).equals(BytesUtils.toHexString(secret1.publicImage().bytes))) Optional.of(secret1)
      else if(BytesUtils.toHexString(prop.bytes).equals(BytesUtils.toHexString(secret2.publicImage().bytes))) Optional.of(secret2)
      else if(BytesUtils.toHexString(prop.bytes).equals(BytesUtils.toHexString(secret3.publicImage().bytes))) Optional.of(secret3)
      else if(BytesUtils.toHexString(prop.bytes).equals(BytesUtils.toHexString(secret4.publicImage().bytes))) Optional.of(secret4)
      else Optional.empty()
    })

    Mockito.when(wallet.boxesOfType(ArgumentMatchers.any(), ArgumentMatchers.any())).thenAnswer(asw => {
      allBoxes
    })

    wallet
  }

  private def getTransaction(fee: Long): RegularTransaction = {
    val from: util.List[Pair[ZenBox, PrivateKey25519]] = new util.ArrayList[Pair[ZenBox, PrivateKey25519]]()
    val to: JList[BoxData[_ <: Proposition, _ <: Box[_ <: Proposition]]] = new JArrayList()

    from.add(new Pair(box_1, secret1))
    from.add(new Pair(box_2, secret2))

    to.add(new ZenBoxData(secret3.publicImage(), box_1.value() + box_2.value() - fee))

    RegularTransaction.create(from, to, fee)
  }

  private def getTransactionList: util.List[RegularTransaction] = {
    val list: util.List[RegularTransaction] = new util.ArrayList[RegularTransaction]()
    list.add(getTransaction(1L))
    list.add(getTransaction(1L))
    list
  }

  def getNodeMemoryPoolMock(sidechainApiMockConfiguration: SidechainApiMockConfiguration): NodeMemoryPool = {
    val memoryPool: NodeMemoryPool = mock[NodeMemoryPool]

    Mockito.when(memoryPool.getTransactions).thenAnswer(_ => transactionList)

    Mockito.when(memoryPool.getTransactionsSortedByFee(ArgumentMatchers.any())).thenAnswer(_ => {
      if (sidechainApiMockConfiguration.getShould_history_getTransactionsSortedByFee_return_value())
        transactionList.asScala.sortBy(_.fee()).asJava
      else null
    })

    Mockito.when(memoryPool.getTransactionById(ArgumentMatchers.any[String])).thenAnswer(asw => {
      if (sidechainApiMockConfiguration.getShould_memPool_searchTransactionInMemoryPool_return_value()) {
        val id = asw.getArgument(0).asInstanceOf[String]
        Optional.ofNullable(Try(transactionList.asScala.filter(tx => BytesUtils.toHexString(idToBytes(ModifierId @@ tx.id)).equalsIgnoreCase(id)).head).getOrElse(null))
      } else
        Optional.empty()
    })

    memoryPool
  }

  def getSidechainNodeView(sidechainApiMockConfiguration: SidechainApiMockConfiguration): SidechainNodeView =
      new SidechainNodeView(
        getNodeHistoryMock(sidechainApiMockConfiguration),
        getNodeStateMock(sidechainApiMockConfiguration),
        getNodeWalletMock(sidechainApiMockConfiguration),
        getNodeMemoryPoolMock(sidechainApiMockConfiguration),
        mock[ApplicationState],
        mock[ApplicationWallet])

}
