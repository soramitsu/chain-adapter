/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.chainadapter.client;

import iroha.protocol.BlockOuterClass;
import lombok.NonNull;

/**
 * Class that holds Iroha block subscription data
 */
public class BlockSubscription {
    private final BlockOuterClass.Block block;
    private final BlockAcknowledgment acknowledgment;

    /**
     * Main constructor
     *
     * @param block          Iroha block
     * @param acknowledgment acknowledgment function
     */
    public BlockSubscription(@NonNull BlockOuterClass.Block block, @NonNull BlockAcknowledgment acknowledgment) {
        this.block = block;
        this.acknowledgment = acknowledgment;
    }

    public BlockOuterClass.Block getBlock() {
        return block;
    }

    public BlockAcknowledgment getAcknowledgment() {
        return acknowledgment;
    }

    @FunctionalInterface
    public interface BlockAcknowledgment {
        void ack();
    }
}
