/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.chainadapter.client;

import io.reactivex.Observable;
import kotlin.Unit;
import lombok.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Class that wraps ReliableIrohaChainListener
 */
public class ReliableIrohaChainListener4J implements Closeable {

    private final ReliableIrohaChainListener reliableIrohaChainListener;

    /**
     * @param rmqConfig               RabbitMQ configuration
     * @param irohaQueue              queue that will be bound to Iroha blocks exchange
     * @param consumerExecutorService executor that is used to execure RabbitMQ consumer code.
     * @param autoAck                 turns on/off auto acknowledgment mode
     * @param onRQMFail               function that will be called on RabbitMQ failure
     */
    public ReliableIrohaChainListener4J(@NonNull RMQConfig rmqConfig,
                                        @NonNull String irohaQueue,
                                        @NonNull ExecutorService consumerExecutorService,
                                        boolean autoAck,
                                        @NonNull Runnable onRQMFail) {
        reliableIrohaChainListener = new ReliableIrohaChainListener(
                rmqConfig,
                irohaQueue,
                consumerExecutorService,
                autoAck, () -> {
            onRQMFail.run();
            return Unit.INSTANCE;
        });
    }

    /**
     * @param rmqConfig  RabbitMQ configuration
     * @param irohaQueue queue that will be bound to Iroha blocks exchange
     * @param autoAck    turns on/off auto acknowledgment mode
     */
    public ReliableIrohaChainListener4J(@NonNull RMQConfig rmqConfig,
                                        @NonNull String irohaQueue,
                                        boolean autoAck) {
        reliableIrohaChainListener = new ReliableIrohaChainListener(rmqConfig, irohaQueue, autoAck);
    }

    /**
     * Starts an RMQ consuming process.
     * The function MUST not be called more than once. Otherwise, [IllegalStateException] will be thrown.
     *
     * @throws IllegalStateException if it's not possible to listen
     */
    public void listen() {
        try {
            reliableIrohaChainListener.listen().get();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start listening blocks", e);
        }
    }

    /**
     * Returns observable that may be used to define subscribers
     *
     * @return observable object that may be used to register subscribers
     */
    public Observable<BlockSubscription> getBlockObservable() {
        return reliableIrohaChainListener.getBlockObservable().get().map(
                (pair) -> new BlockSubscription(pair.getFirst(), () -> pair.getSecond().invoke()));
    }

    @Override
    public void close() throws IOException {
        reliableIrohaChainListener.close();
    }
}
