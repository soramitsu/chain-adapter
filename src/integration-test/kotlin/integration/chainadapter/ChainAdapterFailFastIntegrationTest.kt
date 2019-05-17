package integration.chainadapter

import integration.chainadapter.environment.ChainAdapterIntegrationTestEnvironment
import integration.chainadapter.environment.DEFAULT_RMQ_PORT
import org.junit.jupiter.api.*
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChainAdapterFailFastIntegrationTest {

    private val environment = ChainAdapterIntegrationTestEnvironment()
    private val chainAdapterContainer = environment.createChainAdapterContainer()

    @BeforeAll
    fun setUp() {
        // Mount Iroha keys
        chainAdapterContainer.addFileSystemBind(
            "${environment.userDir}/deploy/iroha/keys/",
            "/opt/chain-adapter/deploy/iroha/keys",
            BindMode.READ_ONLY
        )

        // Mount last read block file
        chainAdapterContainer.addFileSystemBind(
            environment.chainAdapterConfigHelper.createTestLastReadBlockFile(),
            "/opt/chain-adapter/deploy/chain-adapter/last_read_block.txt",
            BindMode.READ_WRITE
        )

        // Set RMQ host
        chainAdapterContainer.addEnv("CHAIN-ADAPTER_RMQHOST", "localhost")
        chainAdapterContainer.addEnv(
            "CHAIN-ADAPTER_RMQPORT",
            environment.rmq.getMappedPort(DEFAULT_RMQ_PORT).toString()
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
     * @given chain adapter and Iroha services being started
     * @when Iroha dies
     * @then chain adapter dies as well
     */
    @Test
    fun testFailFast() {
        // Let the service work a little
        Thread.sleep(15_000)
        Assertions.assertTrue(chainAdapterContainer.isRunning)
        // Kill Iroha
        environment.irohaContainer.stop()
        // Wait a little
        Thread.sleep(5_000)
        // Check that the service is dead
        Assertions.assertFalse(chainAdapterContainer.isRunning)
    }
}