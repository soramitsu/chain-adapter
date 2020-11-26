package com.d3.chainadapter.dedup.hazelcast

class LockAcquireTimeoutException(override var message: String) : Exception() {

    constructor(timeoutSeconds: Long) : this("Could not acquire lock during timeout $timeoutSeconds seconds")

}
