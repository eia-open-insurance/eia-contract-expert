package com.eia.contract.expert;

import java.util.UUID;

/** The caller's expected version does not match the stored row. */
public class ConcurrentContractUpdateException extends RuntimeException {
    private final UUID contractId;

    public ConcurrentContractUpdateException(UUID contractId) {
        super("Contract was modified by another transaction: " + contractId);
        this.contractId = contractId;
    }

    public UUID contractId() {
        return contractId;
    }
}
