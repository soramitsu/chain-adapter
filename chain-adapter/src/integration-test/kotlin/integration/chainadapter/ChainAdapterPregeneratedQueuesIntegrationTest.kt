/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.failure
import integration.chainadapter.environment.ChainAdapterIntegrationTestEnvironment
import mu.KLogging
import org.junit.Assert.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChainAdapterPregeneratedQueuesIntegrationTest {

    private val environment = ChainAdapterIntegrationTestEnvironment()

    private val queueNames = listOf("q1", "q2", "q3", "q4", "q5")

    @AfterAll
    fun tearDown() {
        environment.close()
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter
     * @when new transactions appear in Iroha blockchain
     * @then RabbitMQ consumer reads new transactions
     * in the same order as they were published using pregenerated queues
     */
    @Test
    fun testRuntimeBlocksWerePublishedInPregeneratedQueues() {
        val transactions = 10

        val chainAdapter = environment.createAdapter()
        chainAdapter.init {}.failure { ex -> throw ex }
        // Send transactions
        repeat(transactions) {
            environment.createDummyTransaction()
        }

        queueNames.forEach { queue ->
            val consumedBlocks = Collections.synchronizedList(ArrayList<Long>())
            ReliableIrohaChainListener(
                environment.mapToRMQConfig(chainAdapter.chainAdapterConfig),
                queue,
                createPrettySingleThreadPool(
                    CHAIN_ADAPTER_SERVICE_NAME, "iroha-blocks-consumer"
                ),
                autoAck = true,
                onRmqFail = {}
            ).use { reliableChainListener ->
                reliableChainListener.getBlockObservable().get().subscribe { (block, _) ->
                    consumedBlocks.add(block.blockV1.payload.height)
                }
                //Start consuming
                reliableChainListener.listen()
                //Wait a little until consumed
                Thread.sleep(2_000)
                logger.info { consumedBlocks }
                // 10 transactions and genesis
                assertEquals(transactions + 1, consumedBlocks.size)
                assertEquals(consumedBlocks.sorted(), consumedBlocks)
                assertEquals(
                    chainAdapter.getLastReadBlock(),
                    chainAdapter.lastReadBlockProvider.getLastBlockHeight()
                )
                assertEquals(consumedBlocks.last(), chainAdapter.getLastReadBlock().toLong())
            }
        }

        chainAdapter.close()
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
