/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.chainadapter.client.ReliableIrohaChainListener4J
import com.d3.commons.util.createPrettySingleThreadPool
import com.d3.commons.util.getRandomString
import com.github.kittinunf.result.failure
import integration.chainadapter.environment.ChainAdapterIntegrationTestEnvironment
import mu.KLogging
import org.junit.Assert.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChainAdapterIntegrationTest {

    private val environment = ChainAdapterIntegrationTestEnvironment()

    @AfterAll
    fun tearDown() {
        environment.close()
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter
     * @when new transactions appear in Iroha blockchain
     * @then RabbitMQ consumer reads new transactions
     * in the same order as they were published
     */
    @Test
    fun testRuntimeBlocksWerePublished() {
        val transactions = 10
        val queueName = String.getRandomString(5)
        val consumedBlocks = Collections.synchronizedList(ArrayList<Long>())
        environment.createAdapter().use { adapter ->
            adapter.init {}.failure { ex -> throw ex }
            ReliableIrohaChainListener(
                environment.mapToRMQConfig(adapter.chainAdapterConfig),
                queueName,
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
                repeat(transactions) {
                    environment.createDummyTransaction()
                }
                //Wait a little until consumed
                Thread.sleep(2_000)
                logger.info { consumedBlocks }
                assertEquals(transactions, consumedBlocks.size)
                assertEquals(consumedBlocks.sorted(), consumedBlocks)
                assertEquals(
                    adapter.getLastReadBlock(),
                    adapter.lastReadBlockProvider.getLastBlockHeight()
                )
                assertEquals(consumedBlocks.last(), adapter.getLastReadBlock().toLong())
            }
        }
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter
     * @when new transactions appear in Iroha blockchain, but consumer doesn't acknowledge it
     * @then RabbitMQ consumer reads the first transaction and stop consuming next transactions,
     * because the first transaction was not acknowledged
     */
    @Test
    fun testRuntimeBlocksNoAck() {
        val transactions = 10
        val queueName = String.getRandomString(5)
        val consumedBlocks = Collections.synchronizedList(ArrayList<Long>())
        environment.createAdapter().use { adapter ->
            adapter.init {}.failure { ex -> throw ex }
            ReliableIrohaChainListener(
                environment.mapToRMQConfig(adapter.chainAdapterConfig),
                queueName,
                createPrettySingleThreadPool(
                    CHAIN_ADAPTER_SERVICE_NAME, "iroha-blocks-consumer"
                ),
                autoAck = false,
                onRmqFail = {}
            ).use { reliableChainListener ->
                reliableChainListener.getBlockObservable().get().subscribe { (block, _) ->
                    consumedBlocks.add(block.blockV1.payload.height)
                }
                //Start consuming
                reliableChainListener.listen()
                repeat(transactions) {
                    environment.createDummyTransaction()
                }
                //Wait a little until consumed
                Thread.sleep(2_000)
                logger.info { consumedBlocks }
                assertEquals(1, consumedBlocks.size)
            }
        }
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter
     * @when new transactions appear in Iroha blockchain and consumer acknowledges every transaction manually
     * @then RabbitMQ consumer reads new transactions
     * in the same order as they were published
     */
    @Test
    fun testRuntimeBlocksManualAck() {
        val transactions = 10
        val queueName = String.getRandomString(5)
        val consumedBlocks = Collections.synchronizedList(ArrayList<Long>())
        environment.createAdapter().use { adapter ->
            adapter.init {}.failure { ex -> throw ex }
            ReliableIrohaChainListener(
                environment.mapToRMQConfig(adapter.chainAdapterConfig),
                queueName,
                createPrettySingleThreadPool(
                    CHAIN_ADAPTER_SERVICE_NAME, "iroha-blocks-consumer"
                ),
                autoAck = false,
                onRmqFail = {}
            ).use { reliableChainListener ->
                reliableChainListener.getBlockObservable().get().subscribe { (block, ack) ->
                    consumedBlocks.add(block.blockV1.payload.height)
                    ack()
                }
                //Start consuming
                reliableChainListener.listen()
                repeat(transactions) {
                    environment.createDummyTransaction()
                }
                //Wait a little until consumed
                Thread.sleep(2_000)
                logger.info { consumedBlocks }
                assertEquals(transactions, consumedBlocks.size)
                assertEquals(consumedBlocks.sorted(), consumedBlocks)
                assertEquals(
                    adapter.getLastReadBlock(),
                    adapter.lastReadBlockProvider.getLastBlockHeight()
                )
                assertEquals(consumedBlocks.last(), adapter.getLastReadBlock().toLong())
            }
        }
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter and ReliableIrohaChainListener4J
     * @when new transactions appear in Iroha blockchain and consumer acknowledges every transaction manually
     * @then RabbitMQ consumer reads new transactions
     * in the same order as they were published
     */
    @Test
    fun testRuntimeBlocksManualAck4j() {
        val transactions = 10
        val queueName = String.getRandomString(5)
        val consumedBlocks = Collections.synchronizedList(ArrayList<Long>())
        environment.createAdapter().use { adapter ->
            adapter.init {}.failure { ex -> throw ex }
            ReliableIrohaChainListener4J(
                environment.mapToRMQConfig(adapter.chainAdapterConfig),
                queueName,
                createPrettySingleThreadPool(
                    CHAIN_ADAPTER_SERVICE_NAME, "iroha-blocks-consumer"
                ),
                false,
                {}
            ).use { reliableChainListener ->
                reliableChainListener.getBlockObservable().subscribe { subscription ->
                    consumedBlocks.add(subscription.block.blockV1.payload.height)
                    subscription.acknowledgment.ack()
                }
                //Start consuming
                reliableChainListener.listen()
                repeat(transactions) {
                    environment.createDummyTransaction()
                }
                //Wait a little until consumed
                Thread.sleep(2_000)
                logger.info { consumedBlocks }
                assertEquals(transactions, consumedBlocks.size)
                assertEquals(consumedBlocks.sorted(), consumedBlocks)
                assertEquals(
                    adapter.getLastReadBlock(),
                    adapter.lastReadBlockProvider.getLastBlockHeight()
                )
                assertEquals(consumedBlocks.last(), adapter.getLastReadBlock().toLong())
            }
        }
    }


    /**
     * Logger
     */
    companion object : KLogging()
}
