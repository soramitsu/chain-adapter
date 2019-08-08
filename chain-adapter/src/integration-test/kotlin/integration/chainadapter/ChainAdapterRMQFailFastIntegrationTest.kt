/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter

import integration.chainadapter.environment.ChainAdapterIntegrationTestEnvironment
import integration.chainadapter.environment.DEFAULT_RMQ_PORT
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.BindMode

private const val LAST_READ_BLOCK_FILE = "deploy/chain-adapter/last_read_block.txt"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChainAdapterRMQFailFastIntegrationTest {

    private val environment = ChainAdapterIntegrationTestEnvironment()

    private val chainAdapterContainer = environment.createChainAdapterContainer()

    @BeforeAll
    fun setUp() {
        // Mount last read block file
        chainAdapterContainer.addFileSystemBind(
            LAST_READ_BLOCK_FILE,
            "/deploy/chain-adapter/last_read_block.txt",
            BindMode.READ_WRITE
        )

        // Set RMQ host
        chainAdapterContainer.addEnv("CHAIN-ADAPTER_RMQHOST", "localhost")
        chainAdapterContainer.addEnv(
            "CHAIN-ADAPTER_RMQPORT",
            environment.containerHelper.rmqContainer.getMappedPort(DEFAULT_RMQ_PORT).toString()
        )
        // Set Iroha host and port
        chainAdapterContainer.addEnv("CHAIN-ADAPTER_IROHA_HOSTNAME", "localhost")
        chainAdapterContainer.addEnv(
            "CHAIN-ADAPTER_IROHA_PORT",
            environment.irohaContainer.toriiAddress.port.toString()
        )
        chainAdapterContainer.start()
    }

    @AfterAll
    fun tearDown() {
        chainAdapterContainer.stop()
        environment.close()
    }

    /**
     * @given chain adapter and RMQ services being started
     * @when RMQ dies
     * @then chain adapter dies as well
     */
    @Test
    fun testFailFast() {
        // Let the service work a little
        Thread.sleep(15_000)
        assertTrue(environment.containerHelper.isServiceHealthy(chainAdapterContainer))
        // Kill RMQ
        environment.containerHelper.rmqContainer.stop()
        // Wait a little
        Thread.sleep(15_000)
        // Check that the service is dead
        assertTrue(environment.containerHelper.isServiceDead(chainAdapterContainer))
    }
}
