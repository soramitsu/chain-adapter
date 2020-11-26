/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.chainadapter.adapter

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.config.ChainAdapterConfig
import com.d3.chainadapter.dedup.BlockProcessor
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.getErrorMessage
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import com.rabbitmq.client.*
import com.rabbitmq.client.impl.DefaultExceptionHandler
import io.reactivex.schedulers.Schedulers
import iroha.protocol.BlockOuterClass
import iroha.protocol.QryResponses
import jp.co.soramitsu.iroha.java.ErrorResponseException
import mu.KLogging
import org.springframework.stereotype.Component
import java.io.Closeable
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

const val BAD_IROHA_BLOCK_HEIGHT_ERROR_CODE = 3

/**
 * Chain adapter service
 * It reads Iroha blocks and sends them to recipients via RabbitMQ
 */
@Component
class ChainAdapter(
    private val chainAdapterConfig: ChainAdapterConfig,
    private val irohaQueryHelper: IrohaQueryHelper,
    private val irohaChainListener: IrohaChainListener,
    private val blockProcessor: BlockProcessor
) : Closeable {

    private val connectionFactory = ConnectionFactory()

    private val publishUnreadLatch = CountDownLatch(1)
    private val subscriberExecutorService = createPrettySingleThreadPool(
        CHAIN_ADAPTER_SERVICE_NAME, "iroha-chain-subscriber"
    )
    private val connection: Connection
    private val channel: Channel

    private val lastReadBlock = AtomicReference<BigInteger>(BigInteger.ZERO)

    init {
        if (chainAdapterConfig.username != null && chainAdapterConfig.password != null) {
            logger.info("Authenticate user '${chainAdapterConfig.username}'")
            connectionFactory.password = chainAdapterConfig.password
            connectionFactory.username = chainAdapterConfig.username
            if (chainAdapterConfig.virtualHost != null) {
                logger.info("Virtual host is '${chainAdapterConfig.virtualHost}'")
                connectionFactory.virtualHost = chainAdapterConfig.virtualHost
            }
        }
        // Handle connection errors
        connectionFactory.exceptionHandler = object : DefaultExceptionHandler() {
            override fun handleConnectionRecoveryException(conn: Connection, exception: Throwable) {
                logger.error("RMQ connection error", exception)
                exitProcess(1)
            }

            override fun handleUnexpectedConnectionDriverException(
                conn: Connection,
                exception: Throwable
            ) {
                logger.error("RMQ connection error", exception)
                exitProcess(1)
            }
        }
        connectionFactory.host = chainAdapterConfig.rmqHost
        connectionFactory.port = chainAdapterConfig.rmqPort

        connection = connectionFactory.newConnection()
        channel = connection.createChannel()

        channel.exchangeDeclare(chainAdapterConfig.irohaExchange, BuiltinExchangeType.FANOUT, true)
        chainAdapterConfig.queuesToCreate.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { queue ->
                channel.queueDeclare(queue, true, false, false, null)
                channel.queueBind(queue, chainAdapterConfig.irohaExchange, "")
            }
    }

    /**
     * Initiates and runs chain adapter
     * @param onIrohaListenError - function that will be called on Iroha chain listener error
     */
    fun init(onIrohaListenError: (Throwable) -> Unit): Result<Unit, Exception> {
        return Result.of {
            if (chainAdapterConfig.dropLastReadBlock) {
                logger.info { "Drop last block" }
                blockProcessor.dropBlocksHeight()
            }
            logger.info { "Listening Iroha blocks" }
            initIrohaChainListener(onIrohaListenError)
            publishUnreadIrohaBlocks()
        }
    }

    /**
     * Initiates Iroha chain listener logic
     * @param onIrohaListenError - function that will be called on Iroha chain listener error
     */
    private fun initIrohaChainListener(onIrohaListenError: (Throwable) -> Unit) {
        irohaChainListener.getBlockObservable()
            .map { observable ->
                observable.subscribeOn(Schedulers.from(subscriberExecutorService))
                    .subscribe({ block ->
                        publishUnreadLatch.await()
                        blockProcessor.onNewBlock(block, this::publishToRmq)
                    }, { ex ->
                        logger.error("Error on Iroha chain listener occurred", ex)
                        onIrohaListenError(ex)
                    })
            }
    }

    /**
     * Publishes unread blocks
     */
    private fun publishUnreadIrohaBlocks() {
        var lastProcessedBlock = blockProcessor.getLastBlockHeight()
        var donePublishing = false
        while (!donePublishing) {
            lastProcessedBlock++
            logger.info { "Try read Iroha block $lastProcessedBlock" }

            irohaQueryHelper.getBlock(lastProcessedBlock).fold({ response ->
                blockProcessor.onNewBlock(response.block, this::publishToRmq)
            }, { ex ->
                if (ex is ErrorResponseException) {
                    val errorResponse = ex.errorResponse
                    if (isNoMoreBlocksError(errorResponse)) {
                        logger.info { "Done publishing unread blocks" }
                        donePublishing = true
                    } else {
                        throw Exception("Cannot get block. ${getErrorMessage(errorResponse)}")
                    }
                } else {
                    throw ex
                }
            })
        }
        publishUnreadLatch.countDown()
    }

    fun publishToRmq(block: BlockOuterClass.Block) {
        channel.basicPublish(
                chainAdapterConfig.irohaExchange,
                "",
                MessageProperties.MINIMAL_PERSISTENT_BASIC,
                block.toByteArray()
        )
    }

    /**
     * Returns height of last read Iroha block
     */
    fun getLastReadBlock(): Long = blockProcessor.getLastBlockHeight()

    /**
     * Checks if no more blocks
     * @param errorResponse - error response to check
     * @return true if no more blocks to read
     */
    private fun isNoMoreBlocksError(errorResponse: QryResponses.ErrorResponse) =
        errorResponse.errorCode == BAD_IROHA_BLOCK_HEIGHT_ERROR_CODE

    override fun close() {
        subscriberExecutorService.shutdownNow()
        irohaChainListener.close()
        channel.close()
        channel.connection.close()
    }

    companion object : KLogging()
}
