package com.d3.chainadapter.dedup

import iroha.protocol.BlockOuterClass.Block

/**
 * Common interface for working with consumed blocks. In general underlying implementations must not
 * publish to RMQ if such block was processed before
 */
interface BlockProcessor {

    /**
     * New Iroha block handler
     */
    fun onNewBlock(block: Block, publish : (block: Block) -> Unit)

    /**
     * Return height of last processed block
     */
    fun getLastBlockHeight() : Long

    /**
     * Drop (set to zero) number of last processed block
     */
    fun dropBlocksHeight()

}
