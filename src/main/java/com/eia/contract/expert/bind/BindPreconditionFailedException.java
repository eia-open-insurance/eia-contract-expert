package com.eia.contract.expert.bind;

import com.eia.contract.expert.ContractConflictException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** A bind attempt failed one or more preconditions. Mapped to 409 with the failed checks. */
public class BindPreconditionFailedException extends ContractConflictException {

    private final transient List<BindCheck> failed;

    public BindPreconditionFailedException(UUID contractId, String status, List<BindCheck> failed) {
        super("BIND_PRECONDITION_FAILED",
                "Contract " + contractId + " cannot bind in status " + status + ": "
                        + failed.stream().map(BindCheck::code).collect(Collectors.joining(", ")),
                "contractId", "All bind preconditions must pass (SPEC CTR-01, CTR-02, CTR-11, CTR-15)");
        this.failed = List.copyOf(failed);
    }

    public List<BindCheck> failed() {
        return failed;
    }
}
