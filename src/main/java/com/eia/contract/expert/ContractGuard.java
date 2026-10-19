package com.eia.contract.expert;

import java.util.Set;

/**
 * Lifecycle gates for the contract's child resources.
 *
 * <p>Economic terms are editable until the contract is signed (SPEC CTR-16: "a
 * signed contract's economic terms are immutable"). Cessions belong to a bound or
 * later contract, and a commutation is executed only from a state that the SPEC
 * 11.3.6 diagram lets transition to {@code COMMUTED}.</p>
 */
public final class ContractGuard {

    private static final Set<String> EDITABLE =
            Set.of("DRAFT", "QUOTED", "PLACED", "BOUND");

    private static final Set<String> CESSION_ALLOWED =
            Set.of("BOUND", "SIGNED", "IN_FORCE", "ENDORSED", "RUNOFF");

    private static final Set<String> COMMUTATION_PROPOSABLE =
            Set.of("BOUND", "SIGNED", "IN_FORCE", "ENDORSED", "EXPIRED", "RUNOFF");

    /** States from which SPEC 11.3.6 allows a transition to {@code COMMUTED}. */
    private static final Set<String> COMMUTATION_EXECUTABLE =
            Set.of("IN_FORCE", "RUNOFF");

    private ContractGuard() {
    }

    public static void requireEditable(Contract contract) {
        if (!EDITABLE.contains(contract.status())) {
            throw new ContractConflictException("CONTRACT_LOCKED",
                    "Contract " + contract.contractNumber() + " is " + contract.status()
                            + "; its economic terms can no longer be changed",
                    "status", "Allowed statuses: " + EDITABLE);
        }
    }

    public static void requireCessionAllowed(Contract contract) {
        if (!CESSION_ALLOWED.contains(contract.status())) {
            throw new ContractConflictException("CONTRACT_NOT_INCEPTED",
                    "Contract " + contract.contractNumber() + " is " + contract.status()
                            + "; a cession requires a bound or in-force contract",
                    "status", "Allowed statuses: " + CESSION_ALLOWED);
        }
    }

    public static void requireCommutationProposable(Contract contract) {
        if (!COMMUTATION_PROPOSABLE.contains(contract.status())) {
            throw new ContractConflictException("COMMUTATION_NOT_PERMITTED",
                    "Contract " + contract.contractNumber() + " is " + contract.status()
                            + "; a commutation cannot be proposed",
                    "status", "Allowed statuses: " + COMMUTATION_PROPOSABLE);
        }
    }

    public static void requireCommutationExecutable(Contract contract) {
        if (!COMMUTATION_EXECUTABLE.contains(contract.status())) {
            throw new ContractConflictException("COMMUTATION_NOT_PERMITTED",
                    "Contract " + contract.contractNumber() + " is " + contract.status()
                            + "; only an in-force or run-off contract can be commuted",
                    "status", "Allowed statuses: " + COMMUTATION_EXECUTABLE);
        }
    }
}
