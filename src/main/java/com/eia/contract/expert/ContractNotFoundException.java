package com.eia.contract.expert;

import java.util.UUID;

/** The requested contract does not exist for the tenant. */
public class ContractNotFoundException extends RuntimeException {
    private final UUID contractId;

    public ContractNotFoundException(UUID contractId) {
        super("Contract not found: " + contractId);
        this.contractId = contractId;
    }

    public UUID contractId() {
        return contractId;
    }
}
