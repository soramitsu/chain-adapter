/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("ChainAdapterMain")

package com.d3.chainadapter

import com.d3.chainadapter.adapter.ChainAdapter
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import mu.KLogging
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ComponentScan
import kotlin.system.exitProcess

private val logger = KLogging().logger
const val CHAIN_ADAPTER_SERVICE_NAME = "chain-adapter"

@ComponentScan(
    basePackages = ["com.d3.chainadapter"]
)
class ChainAdapterApp

fun main() {

    Result.of {
        AnnotationConfigApplicationContext(ChainAdapterApp::class.java)
    }.map { context ->
        val adapter = context.getBean(ChainAdapter::class.java)
        adapter.init {
            logger.error("Iroha failure. Exit.")
            exitProcess(1)
        }.fold(
            { logger.info("Chain-adapter has been started") },
            { ex ->
                adapter.close()
                throw ex
            })
    }.failure { ex ->
        logger.error("Cannot start chain-adapter", ex)
        exitProcess(1)
    }
}
