package com.d3.chainadapter.dedup.fs

import com.d3.chainadapter.dedup.BlockProcessor
import com.d3.commons.sidechain.provider.FileBasedLastReadBlockProvider
import iroha.protocol.BlockOuterClass.Block
import mu.KLogging
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * This implementation persist information about last processed block in file, so this is not allow
 * to deduplicate consumed Iroha blocks between multiple instances of service.
 */
class FsBlockProcessor(
        private val delegate: FileBasedLastReadBlockProvider
) : BlockProcessor {

    private val lastReadBlock = AtomicLong(0)

    override fun onNewBlock(block: Block, action : (block: Block) -> Unit) {
        // Send only not read Iroha blocks
        val newBlockHeight = block.blockV1.payload.height
        if (newBlockHeight > lastReadBlock.get()) {
            action.invoke(block)
            logger.info { "Block $newBlockHeight pushed" }
            // Save last read block
            setBlockHeight(newBlockHeight)
        }
    }

    override fun getLastBlockHeight(): Long = lastReadBlock.get()

    override fun dropBlocksHeight() = setBlockHeight(0L)

    private fun setBlockHeight(height: Long) {
        lastReadBlock.set(height)
        delegate.saveLastBlockHeight(BigInteger.valueOf(height))
    }

    companion object : KLogging()
}
