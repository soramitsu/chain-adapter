package com.d3.chainadapter.client

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates thread factory that may be used in thread pool.
 * Format is [serviceName:purpose:th-(thread number):id-(thread id)]
 * @param serviceName - name of service (withdrawal, deposit and etc)
 * @param purpose - purpose of thread (Iroha listener, Ethereum tx listener and etc)
 * @return thread factory
 */
fun namedThreadFactory(
    serviceName: String,
    purpose: String
): ThreadFactory {
    return object : ThreadFactory {
        private val threadCounter = AtomicInteger(0)
        override fun newThread(runnable: Runnable): Thread {
            val thread = Executors.defaultThreadFactory().newThread(runnable)
            thread.name =
                "$serviceName:$purpose:th-${threadCounter.getAndIncrement()}:id-${thread.id}"
            return thread
        }
    }
}

/**
 * Creates pretty named single threaded pool
 * @param serviceName - name of service (withdrawal, deposit and etc)
 * @param purpose - purpose of thread (Iroha listener, Ethereum tx listener and etc)
 * @return pretty thread pool
 */
fun createPrettySingleThreadPool(
    serviceName: String,
    purpose: String
): ExecutorService {
    return Executors.newSingleThreadExecutor(namedThreadFactory(serviceName, purpose))!!
}
