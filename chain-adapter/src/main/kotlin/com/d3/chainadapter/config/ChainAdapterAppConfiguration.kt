/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.chainadapter.config

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.dedup.BlockProcessor
import com.d3.chainadapter.dedup.fs.FsBlockProcessor
import com.d3.chainadapter.dedup.hazelcast.HazelcastBlockProcessor
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.healthcheck.HealthCheckEndpoint
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.provider.FileBasedLastReadBlockProvider
import com.d3.commons.util.createPrettySingleThreadPool
import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.client.config.ClientNetworkConfig
import io.grpc.ManagedChannelBuilder
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.QueryAPI
import jp.co.soramitsu.iroha.java.Utils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

val chainAdapterConfig =
    loadRawLocalConfigs(
        "chain-adapter",
        ChainAdapterConfig::class.java,
        "chain-adapter.properties"
    )

@Configuration
class ChainAdapterAppConfiguration {

    private val irohaCredential = chainAdapterConfig.irohaCredential
    private val keyPair = Utils.parseHexKeypair(irohaCredential.pubkey, irohaCredential.privkey)

    @Bean
    fun chainAdapterConfig() = chainAdapterConfig

    @Bean
    fun irohaAPI(): IrohaAPI {
        /**
         * It's essential to handle blocks in this service one-by-one.
         * This is why we explicitly set single threaded executor.
         */
        val irohaAPI = IrohaAPI(chainAdapterConfig.iroha.hostname, chainAdapterConfig.iroha.port)
        irohaAPI.setChannelForStreamingQueryStub(
            ManagedChannelBuilder.forAddress(
                chainAdapterConfig.iroha.hostname, chainAdapterConfig.iroha.port
            ).executor(
                createPrettySingleThreadPool(
                    CHAIN_ADAPTER_SERVICE_NAME,
                    "iroha-chain-listener"
                )
            ).usePlaintext().build()
        )
        return irohaAPI
    }

    @Bean
    fun queryAPI() =
        QueryAPI(
            irohaAPI(),
            irohaCredential.accountId,
            keyPair
        )

    @Bean
    fun irohaQueryHelper() = IrohaQueryHelperImpl(queryAPI())

    @Bean
    fun irohaChainListener() = IrohaChainListener(
        irohaAPI(),
        IrohaCredential(irohaCredential.accountId, keyPair)
    )

    @Bean
    fun healthCheckEndpoint() = HealthCheckEndpoint(chainAdapterConfig.healthCheckPort)

    @Bean("deduplicator")
    fun deduplicator(chainAdapterConfig: ChainAdapterConfig): BlockProcessor {
        return if (chainAdapterConfig.clusterEnabled) {
            HazelcastBlockProcessor(HazelcastClient.newHazelcastClient(clientConfig(chainAdapterConfig.clusterHazelcastMembers)))
        } else {
            FsBlockProcessor(FileBasedLastReadBlockProvider(chainAdapterConfig.lastReadBlockFilePath))
        }
    }

    private fun clientConfig(hazelcastMembersRaw: String?): ClientConfig {
        val hazelcastMembers = hazelcastMembersRaw
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toTypedArray()
                ?: throw RuntimeException("At least one Hazelcast node must be specified")
        return ClientConfig()
                .setNetworkConfig(
                        ClientNetworkConfig()
                                .addAddress(*hazelcastMembers)
                                .setSmartRouting(true)
                                .setConnectionTimeout(5000)
                )
    }
}
