/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.client.ReliableIrohaChainListener
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
class ChainAdapterUnreadIntegrationTest {

    private val environment = ChainAdapterIntegrationTestEnvironment()

    @AfterAll
    fun tearDown() {
        environment.close()
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given not running chain-adapter
     * @when 10 dummy transactions appear in Iroha blockchain before and after chain-adapter start
     * @then RabbitMQ consumer reads 20 transactions
     * in the same order as they were published
     */
    @Test
    fun testUnreadBlocksWerePublished() {
        val transactionsBeforeStart = 10
        val transactionsAfterStart = 10
        var transactionsCount = 0
        val queueName = String.getRandomString(5)
        val consumedTransactions = Collections.synchronizedList(ArrayList<Long>())
        environment.createAdapter().use { adapter ->
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
                    block.blockV1.payload.transactionsList.forEach { tx ->
                        tx.payload.reducedPayload.commandsList.forEach { command ->
                            logger.info("Block ${block.blockV1.payload.height} with command $command")
                            if (environment.isDummyCommand(command)) {
                                // Collect dummy transactions
                                // Key is number of transaction
                                consumedTransactions.add(command.setAccountDetail.key.toLong())
                            }
                        }
                    }
                }
                // Start consuming
                reliableChainListener.listen()
                // Before start
                logger.info { "Start send dummy transactions before service start" }
                repeat(transactionsBeforeStart) {
                    environment.createDummyTransaction(testKey = transactionsCount.toString())
                    transactionsCount++
                }
                // Start
                adapter.init {}.failure { ex -> throw ex }
                // After start
                logger.info { "Start send dummy transactions after service start" }
                repeat(transactionsAfterStart) {
                    environment.createDummyTransaction(testKey = transactionsCount.toString())
                    transactionsCount++
                }
                //Wait a little until consumed
                Thread.sleep(2_000)
                logger.info { consumedTransactions }
                assertEquals(
                    transactionsAfterStart + transactionsAfterStart,
                    consumedTransactions.size
                )
                assertEquals(consumedTransactions.sorted(), consumedTransactions)
                assertEquals(
                    adapter.getLastReadBlock(),
                    adapter.lastReadBlockProvider.getLastBlockHeight()
                )
            }
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
