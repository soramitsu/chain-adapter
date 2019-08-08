/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.chainadapter.helper

import com.d3.chainadapter.config.ChainAdapterConfig
import com.d3.commons.config.loadRawLocalConfigs
import java.util.*
import kotlin.math.absoluteValue

private val random = Random()

class ChainAdapterConfigHelper {

    /**
     * Creates chain adapter config
     */
    fun createChainAdapterConfig(rmqHost: String, rmqPort: Int): ChainAdapterConfig {
        val chainAdapterConfig = loadRawLocalConfigs(
            "chain-adapter",
            ChainAdapterConfig::class.java,
            "chain-adapter.properties"
        )
        return object : ChainAdapterConfig {
            // No matter what port. Health check service will not be started in tests.
            override val healthCheckPort=123
            override val rmqHost = rmqHost
            override val rmqPort = rmqPort
            // Random exchange
            override val irohaExchange = random.nextInt().absoluteValue.toString()
            override val irohaCredential = chainAdapterConfig.irohaCredential
            override val iroha = chainAdapterConfig.iroha
            override val lastReadBlockFilePath = chainAdapterConfig.lastReadBlockFilePath
            override val dropLastReadBlock = true
            override val queuesToCreate = "q1 , q2, q3 ,   q4,  q5   "
        }
    }
}
