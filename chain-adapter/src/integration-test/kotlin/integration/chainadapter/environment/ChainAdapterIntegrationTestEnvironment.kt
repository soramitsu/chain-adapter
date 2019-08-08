/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter.environment

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.adapter.ChainAdapter
import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.config.ChainAdapterConfig
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.provider.FileBasedLastReadBlockProvider
import com.d3.commons.sidechain.provider.LastReadBlockProvider
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.failure
import integration.chainadapter.helper.ChainAdapterConfigHelper
import integration.helper.ContainerHelper
import integration.helper.KGenericContainerImage
import io.grpc.ManagedChannelBuilder
import iroha.protocol.BlockOuterClass
import iroha.protocol.Commands
import iroha.protocol.Primitive
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.QueryAPI
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer
import jp.co.soramitsu.iroha.testcontainers.PeerConfig
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.Closeable
import java.io.File
import java.util.*
import kotlin.math.absoluteValue

private val random = Random()
const val DEFAULT_RMQ_PORT = 5672

/**
 * Chain adapter test environment
 */
class ChainAdapterIntegrationTestEnvironment : Closeable {

    val containerHelper = ContainerHelper()
    private val peerKeyPair = Ed25519Sha3().generateKeypair()

    private val chainAdapterConfig = loadRawLocalConfigs(
        "chain-adapter",
        ChainAdapterConfig::class.java,
        "chain-adapter.properties"
    )
    private val rmqKeyPair = Utils.parseHexKeypair(
        chainAdapterConfig.irohaCredential.pubkey,
        chainAdapterConfig.irohaCredential.privkey
    )

    private val dummyClientId = "client@d3"
    private val dummyClientKeyPair = Ed25519Sha3().generateKeypair()
    private val dummyIrohaCredential = IrohaCredential(dummyClientId, dummyClientKeyPair)

    // Random dummy value
    private val dummyValue = random.nextInt().absoluteValue.toString()

    val chainAdapterConfigHelper = ChainAdapterConfigHelper()

    val irohaContainer = IrohaContainer().withPeerConfig(getPeerConfig())

    val userDir = System.getProperty("user.dir")!!
    private val dockerfile = "$userDir/build/docker/Dockerfile"
    private val chainAdapterContextFolder = "$userDir/build/docker/"

    private val irohaAPI: IrohaAPI

    private val dummyIrohaConsumer: IrohaConsumer

    init {
        containerHelper.rmqContainer.start()
        // I don't want to see nasty Iroha logs
        irohaContainer.withLogger(null)
        irohaContainer.start()
        irohaAPI = irohaContainer.api
        dummyIrohaConsumer = IrohaConsumerImpl(dummyIrohaCredential, irohaAPI)
    }

    //TODO move to the main repository
    /**
     * Creates chain adapter docker container
     * @return container
     */
    fun createChainAdapterContainer(): KGenericContainerImage {
        return KGenericContainerImage(
            ImageFromDockerfile()
                .withFileFromFile("", File(chainAdapterContextFolder))
                .withFileFromFile("Dockerfile", File(dockerfile))

        )
            .withLogConsumer { outputFrame -> print(outputFrame.utf8String) }
            .withNetworkMode("host")
    }

    /**
     * Returns Iroha peer config
     */
    private fun getPeerConfig(): PeerConfig {
        val config = PeerConfig.builder()
            .genesisBlock(getGenesisBlock())
            .build()
        config.withPeerKeyPair(peerKeyPair)
        return config
    }

    /**
     * Creates test genesis block
     */
    private fun getGenesisBlock(): BlockOuterClass.Block {
        return GenesisBlockBuilder().addTransaction(
            Transaction.builder("")
                .addPeer("0.0.0.0:10001", peerKeyPair.public)
                .createRole("none", emptyList())
                .createRole(
                    "client",
                    listOf(
                        Primitive.RolePermission.can_set_detail
                    )
                ).createRole(
                    "rmq", listOf(
                        Primitive.RolePermission.can_get_blocks
                    )
                )
                .createDomain("notary", "none")
                .createDomain("d3", "client")
                .createAccount("rmq@notary", rmqKeyPair.public)
                .createAccount(dummyClientId, dummyClientKeyPair.public)
                .appendRole("rmq@notary", "rmq")
                .build()
                .build()
        ).build()
    }

    /**
     * Creates ChainAdapter
     */
    fun createAdapter(): OpenChainAdapter {
        val chainAdapterConfig = chainAdapterConfigHelper.createChainAdapterConfig(
            containerHelper.rmqContainer.containerIpAddress,
            containerHelper.rmqContainer.getMappedPort(DEFAULT_RMQ_PORT)
        )
        val irohaAPI = irohaAPI()
        val lastReadBlockProvider = FileBasedLastReadBlockProvider(chainAdapterConfig.lastReadBlockFilePath)
        val queryAPI =
            QueryAPI(
                irohaAPI,
                chainAdapterConfig.irohaCredential.accountId,
                rmqKeyPair
            )
        val irohaChainListener = IrohaChainListener(
            irohaAPI,
            IrohaCredential(chainAdapterConfig.irohaCredential.accountId, rmqKeyPair)
        )
        return OpenChainAdapter(
            chainAdapterConfig,
            queryAPI,
            irohaChainListener,
            lastReadBlockProvider
        )
    }

    /**
     * It's essential to handle blocks in this service one-by-one.
     * This is why we explicitly set single threaded executor.
     */
    private fun irohaAPI(): IrohaAPI {
        val api = irohaContainer.api
        val irohaAddress = irohaContainer.toriiAddress
        api.setChannelForStreamingQueryStub(
            ManagedChannelBuilder.forAddress(
                irohaAddress.host, irohaAddress.port
            ).executor(
                createPrettySingleThreadPool(
                    CHAIN_ADAPTER_SERVICE_NAME,
                    "iroha-chain-listener"
                )
            ).usePlaintext().build()
        )
        return api
    }

    /**
     * Creates dummy transaction
     */
    fun createDummyTransaction(testKey: String = dummyValue) {
        ModelUtil.setAccountDetail(
            dummyIrohaConsumer,
            dummyClientId,
            testKey,
            dummyValue
        ).failure { ex -> throw ex }
    }

    /**
     * Checks if command is dummy
     */
    fun isDummyCommand(command: Commands.Command): Boolean {
        return command.hasSetAccountDetail() && command.setAccountDetail.value == dummyValue
    }

    /**
     * Maps ChainAdapterConfig to RMQConfig
     * @param chainAdapterConfig - config to map
     * @return RMQConfig based on [chainAdapterConfig]
     */
    fun mapToRMQConfig(chainAdapterConfig: ChainAdapterConfig): RMQConfig {
        return object : RMQConfig {
            override val host = chainAdapterConfig.rmqHost
            override val port = chainAdapterConfig.rmqPort
            override val irohaExchange = chainAdapterConfig.irohaExchange
        }
    }

    override fun close() {
        irohaAPI.close()
        irohaContainer.close()
        containerHelper.close()
    }
}

/**
 * This ChainAdapter implementation is used to expose values
 * that are private in the original class
 */
class OpenChainAdapter(
    val chainAdapterConfig: ChainAdapterConfig,
    queryAPI: QueryAPI,
    irohaChainListener: IrohaChainListener,
    val lastReadBlockProvider: LastReadBlockProvider
) : ChainAdapter(chainAdapterConfig, IrohaQueryHelperImpl(queryAPI), irohaChainListener, lastReadBlockProvider)
