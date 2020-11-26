package com.d3.chainadapter.dedup.hazelcast

import com.d3.chainadapter.dedup.BlockProcessor
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import iroha.protocol.BlockOuterClass.Block
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Name of distributed map and name of the key in the map. Same name for simplicity
 */
const val PROCESSED_BLOCK_ACCESS_KEY = "processed-block-numbers"

/**
 * How long to wait for an exclusive lock. If the timeout is exceeded, an exception is thrown
 */
const val LOCK_AWAITING_TIMEOUT_SECONDS = 30L

/**
 * How many seconds can an exclusive lock be held
 */
const val MAX_LOCK_DURATION_SECONDS = 10L

/**
 * This implementation sends new block to RMQ only if it was not processed before by this or another
 * instance of service. It allows to run multiple instances of the service. Deduplication provided
 * by pessimistic lock on distributed map.
 */
class HazelcastBlockProcessor(private val hazelcastInstance: HazelcastInstance) : BlockProcessor, Closeable {

    private val processedBlockNumber : IMap<String, Long> = hazelcastInstance.getMap(PROCESSED_BLOCK_ACCESS_KEY)

    override fun onNewBlock(block: Block, publish: (block: Block) -> Unit) {
        val newBlockHeight = block.blockV1.payload.height
        pessimisticLock {
            val lastProcessedBlockHeight = getLastBlockHeight()
            when {
                newBlockHeight == lastProcessedBlockHeight + 1  -> {
                    // new block consumed in proper order and must be processed
                    logger.trace { "Try to publish block with height $newBlockHeight" }
                    publish(block)
                    logger.trace { "Published block with height $newBlockHeight" }
                    processedBlockNumber[PROCESSED_BLOCK_ACCESS_KEY] = newBlockHeight
                }
                newBlockHeight <= lastProcessedBlockHeight -> {
                    //new block was processed already, so just skip it
                    logger.trace { "Skipping already processed block with height $newBlockHeight" }
                }
                else -> {
                    // order is incorrect,the height of the new block is more than 1 than the height
                    // of the previously processed block. If error was thrown, check
                    // ChainAdapter.publishUnreadIrohaBlocks
                    throw IllegalOrderException(lastProcessedBlockHeight, newBlockHeight)
                }
            }
        }
    }

    override fun getLastBlockHeight(): Long = processedBlockNumber.getOrDefault(PROCESSED_BLOCK_ACCESS_KEY, 0L)

    override fun dropBlocksHeight() = setLastProcessedBlock(0L)

    fun setLastProcessedBlock(valueToSet: Long) {
        pessimisticLock {
            processedBlockNumber[PROCESSED_BLOCK_ACCESS_KEY] = valueToSet
        }
    }

    private fun pessimisticLock (exec : () -> Unit) {
        var locked = false
        try {
            locked = processedBlockNumber.tryLock(
                    PROCESSED_BLOCK_ACCESS_KEY,
                    LOCK_AWAITING_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                    MAX_LOCK_DURATION_SECONDS, TimeUnit.SECONDS
            )
            if (locked) {
                exec()
            } else {
                logger.error { "Could not acquire lock during timeout $LOCK_AWAITING_TIMEOUT_SECONDS"}
                throw LockAcquireTimeoutException(LOCK_AWAITING_TIMEOUT_SECONDS)
            }
        } finally {
            if (locked) {
                logger.trace { "Releasing lock on last processed block number" }
                processedBlockNumber.unlock(PROCESSED_BLOCK_ACCESS_KEY)
            }
        }
    }

    override fun close() = hazelcastInstance.shutdown()

    companion object : KLogging()
}
