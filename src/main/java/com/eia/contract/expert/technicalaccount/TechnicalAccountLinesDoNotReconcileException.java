package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ApiError;
import com.eia.contract.expert.ContractConflictException;

import java.util.List;

/**
 * A DRAFT technical account has lines, but at least one bucket total does not
 * equal its matching header component, so the account cannot be issued.
 *
 * <p>The conflict carries one {@link ApiError.FieldViolation} per offending
 * bucket instead of a single field detail, so the caller sees the expected
 * header amount and the actual bucket total for every component at once. The
 * detail message names the line types that roll into the bucket.</p>
 */
public class TechnicalAccountLinesDoNotReconcileException extends ContractConflictException {

    private final List<ApiError.FieldViolation> mismatches;

    public TechnicalAccountLinesDoNotReconcileException(
            String accountNumber, List<ApiError.FieldViolation> mismatches) {
        super("ACCOUNT_LINES_DO_NOT_RECONCILE",
                "Technical account " + accountNumber
                        + " line totals do not reconcile to its header amounts",
                "lines", "Each line-type total must equal its matching account component");
        this.mismatches = List.copyOf(mismatches);
    }

    public List<ApiError.FieldViolation> mismatches() {
        return mismatches;
    }
}
