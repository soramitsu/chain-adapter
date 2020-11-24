package com.d3.chainadapter.dedup.hazelcast

class IllegalOrderException(override var message: String) : Exception() {

    constructor(lastProcessedBlockHeight: Long, actualBlockHeight: Long) : this(
            "Unexpected state. Currently received block number is $actualBlockHeight and this is " +
            "greater than 1 than the number of the previously processed block " +
            "($lastProcessedBlockHeight). Blocks with number" +
            " ${(lastProcessedBlockHeight + 1 until actualBlockHeight).joinToString()} must " +
            "be processed first "
    )

}
