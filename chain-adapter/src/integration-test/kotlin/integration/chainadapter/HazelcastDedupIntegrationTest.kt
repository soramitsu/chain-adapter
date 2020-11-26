package integration.chainadapter

import com.d3.chainadapter.ChainAdapterApp
import com.d3.chainadapter.adapter.BAD_IROHA_BLOCK_HEIGHT_ERROR_CODE
import com.d3.chainadapter.adapter.ChainAdapter
import com.d3.chainadapter.dedup.hazelcast.HazelcastBlockProcessor
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import integration.chainadapter.environment.DEFAULT_RMQ_PORT
import integration.chainadapter.environment.TestConfig
import integration.helper.ContainerHelper
import integration.helper.KGenericContainer
import io.mockk.*
import io.reactivex.Observable
import iroha.protocol.BlockOuterClass
import iroha.protocol.QryResponses.ErrorResponse
import jp.co.soramitsu.iroha.java.ErrorResponseException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@SpringBootTest(
        classes = [ChainAdapterApp::class, TestConfig::class],
        properties = ["spring.main.allow-bean-definition-overriding=true"]
)
class HazelcastDedupIntegrationTest {

    //spy
    @Autowired
    lateinit var chainAdapter: ChainAdapter

    //spy
    @Autowired
    lateinit var hazelcastBlockProcessor: HazelcastBlockProcessor

    //spy
    @Autowired
    lateinit var irohaChainListener: IrohaChainListener

    //spy
    @Autowired
    lateinit var irohaQueryHelper: IrohaQueryHelper

    @BeforeEach
    fun clear() {
        hazelcastBlockProcessor.dropBlocksHeight()
        clearAllMocks()
    }

    /**
     * @given chain-adapter listen to Iroha blocks
     * @when new block from Iroha received and such block number was not processed yet
     * @then block is sent to RMQ
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun testNewBlockIsSent() {
        //input blocks for adapter
        val expectedBlocks = prepareBlocksAndRunListener()

        verify(exactly = expectedBlocks.size, timeout = 10000) { hazelcastBlockProcessor.onNewBlock(any(), any()) }
        verifyOrder {
            for (block in expectedBlocks) {
                chainAdapter.publishToRmq(block)
            }
        }
    }

    /**
     * @given chain-adapter listen to Iroha blocks
     * @when new block from Iroha received and such block number already processed
     * @then block is not sent to RMQ
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun testAlreadyProcessedBlockIsNotSent() {
        val blocksCount = 10
        hazelcastBlockProcessor.setLastProcessedBlock(10 / 2)
        //input blocks for adapter
        val expectedBlocks = prepareBlocksAndRunListener(blocksCount)

        verify(exactly = blocksCount, timeout = 10000) { hazelcastBlockProcessor.onNewBlock(any(), any()) }
        verifyOrder {
            for (block in expectedBlocks.drop(10 / 2)) {
                chainAdapter.publishToRmq(block)
            }
        }
    }

    /**
     * @given multiple instances of service listen to Iroha blocks
     * @when blocks are processed concurrently
     * @then order preserved and no block duplicated or missed
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun testConcurrentExecutionPreserveOrder() {
        val blocksCount = 100
        val concurrency = 3
        val preparedImmutableBlocks = prepareBlocks(blocksCount)
        val deduplicatedBlocks = mutableListOf<Long>()
        val jobs = List(concurrency) {
            GlobalScope.launch {
                for (block in preparedImmutableBlocks) {
                    hazelcastBlockProcessor.onNewBlock(block) {
                        deduplicatedBlocks.add(block.blockV1.payload.height)
                    }
                }
            }
        }

        runBlocking {
            jobs.forEach {
                it.join()
            }
        }
        assertEquals(blocksCount, deduplicatedBlocks.size)
        assertEquals(deduplicatedBlocks.sorted(), deduplicatedBlocks)
    }

    /**
     * @given chain-adapter listen to Iroha blocks
     * @when new block from Iroha received and it is not processed before and there is an error during
     * sending block to RMQ
     * @then block number is not saved as processed block
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun testBlockNumberIsNotSavedIfErrorWasThrown() {
        every { chainAdapter.publishToRmq(any()) } throws RuntimeException("Shit happens")

        val onErrorAction = spyk({})
        val blocksCount = 1

        //input blocks for adapter
        prepareBlocksAndRunListener(blocksCount, onErrorAction)

        verify(exactly = blocksCount, timeout = 10000) { hazelcastBlockProcessor.onNewBlock(any(), any()) }
        //check onError action was invoked so that means error was actually thrown
        verify(exactly = 1, timeout = 10000) { onErrorAction() }

        assertEquals(0L, hazelcastBlockProcessor.getLastBlockHeight())
    }

    private fun prepareBlocksAndRunListener(
            tillBlockNumInclusive: Int = 10,
            onErrorAction: () -> Unit = {}
    ): List<BlockOuterClass.Block> {
        val inputBlocks = prepareBlocks(tillBlockNumInclusive)
        every { irohaChainListener.getBlockObservable() } returns Result.of { Observable.fromIterable(inputBlocks) }
        every {
            irohaQueryHelper.getBlock(any())
        } returns (
                Result.error(ErrorResponseException(ErrorResponse.newBuilder().setErrorCode(BAD_IROHA_BLOCK_HEIGHT_ERROR_CODE).build()))
                )
        chainAdapter.init { onErrorAction() }
        return inputBlocks
    }

    private fun prepareBlocks(tillBlockNumInclusive: Int = 10): List<BlockOuterClass.Block> {
        return (1..tillBlockNumInclusive).map {
            BlockOuterClass.Block.newBuilder()
                    .setBlockV1(
                            BlockOuterClass.Block_v1.newBuilder()
                                    .setPayload(
                                            BlockOuterClass.Block_v1.Payload.newBuilder()
                                                    .setHeight(it.toLong())
                                                    .build()
                                    ).build()
                    ).build()
        }
    }

    companion object {
        val rmq = ContainerHelper().rmqFixedPortContainer
        val hazelcast = KGenericContainer("hazelcast/hazelcast:4.0.1")

        @JvmStatic
        @BeforeAll
        fun setUp() {
            rmq.withExposedPorts(DEFAULT_RMQ_PORT).start()
            hazelcast.withExposedPorts(5701).start()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            //not stopping rmq because it is affect other test class execution
            //rmq.stop()
            hazelcast.stop()
        }
    }
}
