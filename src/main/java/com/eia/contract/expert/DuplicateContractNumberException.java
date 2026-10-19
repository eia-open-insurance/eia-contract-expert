package com.eia.contract.expert;

/** The contract number already exists for this tenant (SPEC §11.3.4: unique). */
public class DuplicateContractNumberException extends RuntimeException {
    private final String contractNumber;

    public DuplicateContractNumberException(String contractNumber) {
        super("Contract number already exists for tenant: " + contractNumber);
        this.contractNumber = contractNumber;
    }

    public String contractNumber() {
        return contractNumber;
    }
}
