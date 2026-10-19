package com.eia.contract.expert;

import java.util.Set;

/** A status change is not permitted by the SPEC §11.3.6 lifecycle. */
public class InvalidStatusTransitionException extends RuntimeException {
    private final ContractStatus from;
    private final ContractStatus to;
    private final Set<ContractStatus> allowed;

    public InvalidStatusTransitionException(ContractStatus from, ContractStatus to) {
        super("Contract status cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
        this.allowed = from.allowedNext();
    }

    public ContractStatus from() {
        return from;
    }

    public ContractStatus to() {
        return to;
    }

    public Set<ContractStatus> allowed() {
        return allowed;
    }
}
