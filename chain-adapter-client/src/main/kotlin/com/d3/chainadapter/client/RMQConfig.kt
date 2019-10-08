package com.d3.chainadapter.client

/**
 * RMQ configurations
 */
interface RMQConfig {
    val host: String
    val port: Int
    val irohaExchange: String
    val username: String? get() = null
    val password: String? get() = null
}