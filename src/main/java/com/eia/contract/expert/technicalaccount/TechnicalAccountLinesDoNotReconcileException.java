package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ApiError;
import com.eia.contract.expert.ContractConflictException;

import java.util.List;

/**
 * A DRAFT technical account has lines, but at least one line-type total does not
 * equal its matching header component, so the account cannot be issued.
 *
 * <p>The conflict carries one {@link ApiError.FieldViolation} per offending
 * line type instead of a single field detail, so the caller sees the expected
 * header amount and the actual line total for every type at once.</p>
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
