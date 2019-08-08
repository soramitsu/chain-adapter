/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.chainadapter.adapter

import com.d3.chainadapter.CHAIN_ADAPTER_SERVICE_NAME
import com.d3.chainadapter.config.ChainAdapterConfig
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.getErrorMessage
import com.d3.commons.sidechain.provider.LastReadBlockProvider
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

private const val BAD_IROHA_BLOCK_HEIGHT_ERROR_CODE = 3

/**
 * Chain adapter service
 * It reads Iroha blocks and sends them to recipients via RabbitMQ
 */
@Component
class ChainAdapter(
    private val chainAdapterConfig: ChainAdapterConfig,
    private val irohaQueryHelper: IrohaQueryHelper,
    private val irohaChainListener: IrohaChainListener,
    private val lastReadBlockProvider: LastReadBlockProvider
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
    fun init(onIrohaListenError: () -> Unit): Result<Unit, Exception> {
        return Result.of {
            if (chainAdapterConfig.dropLastReadBlock) {
                logger.info { "Drop last block" }
                lastReadBlockProvider.saveLastBlockHeight(BigInteger.ZERO)
            }
            lastReadBlock.set(lastReadBlockProvider.getLastBlockHeight())
            logger.info { "Listening Iroha blocks" }
            initIrohaChainListener(onIrohaListenError)
            publishUnreadIrohaBlocks()
        }
    }

    /**
     * Initiates Iroha chain listener logic
     * @param onIrohaListenError - function that will be called on Iroha chain listener error
     */
    private fun initIrohaChainListener(onIrohaListenError: () -> Unit) {
        irohaChainListener.getBlockObservable()
            .map { observable ->
                observable.subscribeOn(Schedulers.from(subscriberExecutorService))
                    .subscribe({ block ->
                        publishUnreadLatch.await()
                        // Send only not read Iroha blocks
                        if (block.blockV1.payload.height > lastReadBlock.get().toLong()) {
                            onNewBlock(block)
                        }
                    }, { ex ->
                        logger.error("Error on Iroha chain listener occurred", ex)
                        onIrohaListenError()
                    })
            }
    }

    /**
     * Publishes unread blocks
     */
    private fun publishUnreadIrohaBlocks() {
        var lastProcessedBlock = lastReadBlockProvider.getLastBlockHeight()
        var donePublishing = false
        while (!donePublishing) {
            lastProcessedBlock++
            logger.info { "Try read Iroha block $lastProcessedBlock" }

            irohaQueryHelper.getBlock(lastProcessedBlock.toLong()).fold({ response ->
                onNewBlock(response.block)
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

    /**
     * Checks if no more blocks
     * @param errorResponse - error response to check
     * @return true if no more blocks to read
     */
    private fun isNoMoreBlocksError(errorResponse: QryResponses.ErrorResponse) =
        errorResponse.errorCode == BAD_IROHA_BLOCK_HEIGHT_ERROR_CODE

    /**
     * Publishes new block to RabbitMQ
     * @param block - Iroha block to publish
     */
    private fun onNewBlock(block: BlockOuterClass.Block) {
        val message = block.toByteArray()
        channel.basicPublish(
            chainAdapterConfig.irohaExchange,
            "",
            MessageProperties.MINIMAL_PERSISTENT_BASIC,
            message
        )
        val height = block.blockV1.payload.height
        logger.info { "Block $height pushed" }
        // Save last read block
        lastReadBlockProvider.saveLastBlockHeight(BigInteger.valueOf(height))
        lastReadBlock.set(BigInteger.valueOf(height))
    }

    /**
     * Returns height of last read Iroha block
     */
    fun getLastReadBlock() = lastReadBlock.get()

    override fun close() {
        subscriberExecutorService.shutdownNow()
        irohaChainListener.close()
        channel.close()
        connection.close()
    }

    companion object : KLogging()
}
