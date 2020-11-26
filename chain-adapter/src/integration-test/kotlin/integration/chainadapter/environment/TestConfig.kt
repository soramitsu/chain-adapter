package integration.chainadapter.environment

import com.d3.chainadapter.adapter.ChainAdapter
import com.d3.chainadapter.config.ChainAdapterConfig
import com.d3.chainadapter.dedup.hazelcast.HazelcastBlockProcessor
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import integration.chainadapter.HazelcastDedupIntegrationTest.Companion.hazelcast
import integration.chainadapter.HazelcastDedupIntegrationTest.Companion.rmq
import io.mockk.spyk
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Primary

@Configuration
class TestConfig {

    @Bean
    @Primary
    fun chainAdapterConfig() : ChainAdapterConfig {
        val configs = loadRawLocalConfigs(
                "chain-adapter",
                ChainAdapterConfig::class.java,
                "chain-adapter.properties"
        )
        return MutableChainAdapterConfig(configs)
    }

    @Bean
    @Primary
    @DependsOn("chainAdapter")
    fun spyChainAdapter(chainAdapter: ChainAdapter) : ChainAdapter {
        return spyk(chainAdapter)
    }

    @Bean
    @Primary
    fun spyChainListener(irohaChainListener: IrohaChainListener) : IrohaChainListener {
        return spyk(irohaChainListener)
    }

    @Bean
    @Primary
    fun spyIrohaQueyHelper(irohaQueryHelper: IrohaQueryHelper) : IrohaQueryHelper {
        return spyk(irohaQueryHelper)
    }

    @Bean
    @Primary
    @DependsOn("deduplicator")
    fun spyHazelcastDedup(hazelcastBlockProcessor: HazelcastBlockProcessor) : HazelcastBlockProcessor {
        return spyk(hazelcastBlockProcessor)
    }

    open class MutableChainAdapterConfig(source: ChainAdapterConfig) : ChainAdapterConfig {
        override var healthCheckPort = source.healthCheckPort
        override var rmqHost = rmq.containerIpAddress
        override var rmqPort = rmq.firstMappedPort
        override var irohaExchange = source.irohaExchange
        override var irohaCredential = source.irohaCredential
        override var iroha = source.iroha
        override var lastReadBlockFilePath = source.lastReadBlockFilePath
        override var dropLastReadBlock = source.dropLastReadBlock
        override var queuesToCreate = source.queuesToCreate
        override var username = source.username
        override var password = source.password
        override var clusterEnabled = true
        override var clusterHazelcastMembers = "${hazelcast.containerIpAddress}:${hazelcast.firstMappedPort}"
    }
}
