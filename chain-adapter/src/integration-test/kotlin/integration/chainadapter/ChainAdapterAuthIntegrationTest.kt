/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter

import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.util.getRandomString
import com.github.kittinunf.result.failure
import com.rabbitmq.client.AuthenticationFailureException
import integration.chainadapter.environment.ChainAdapterIntegrationTestEnvironment
import mu.KLogging
import org.junit.Assert.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChainAdapterAuthIntegrationTest {

    private val userName = "abc"
    private val password = "xyz"
    private val environment = ChainAdapterIntegrationTestEnvironment(rmqUsername = userName, rmqPassword = password)

    @AfterAll
    fun tearDown() {
        environment.close()
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter and RMQ credentials
     * @when new transactions appear in Iroha blockchain
     * @then RabbitMQ consumer reads new transactions
     * in the same order as they were published
     */
    @Test
    fun testRuntimeBlocksWerePublishedSuccessfulAuthentication() {
        val transactions = 10
        val queueName = String.getRandomString(5)
        val consumedBlocks = Collections.synchronizedList(ArrayList<Long>())
        environment.createAdapter().use { adapter ->
            adapter.init {}.failure { ex -> throw ex }
            ReliableIrohaChainListener(
                environment.mapToRMQConfig(adapter.chainAdapterConfig),
                queueName,
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
                    adapter.blockProcessor.getLastBlockHeight()
                )
                assertEquals(consumedBlocks.last(), adapter.getLastReadBlock().toLong())
            }
        }
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given running chain-adapter and wrong RMQ credentials
     * @when we try to create ReliableIrohaChainListener
     * @then the listener creation fails with [AuthenticationFailureException]
     */
    @Test
    fun testFailedAuthentication() {
        assertFailsWith(AuthenticationFailureException::class) {
            val queueName = String.getRandomString(5)
            environment.createAdapter().use { adapter ->
                adapter.init {}.failure { ex -> throw ex }
                val originalConfig = environment.mapToRMQConfig(adapter.chainAdapterConfig)
                val modifiedConfig = object : RMQConfig {
                    override val host = originalConfig.host
                    override val port = originalConfig.port
                    override val irohaExchange = originalConfig.irohaExchange
                    override val username = "shrek"
                    override val password = "kek"
                }
                ReliableIrohaChainListener(
                    modifiedConfig,
                    queueName,
                    autoAck = true,
                    onRmqFail = {}
                )
            }
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
