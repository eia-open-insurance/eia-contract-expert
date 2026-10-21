package com.eia.contract.expert.security;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Validation for the public collateral API.
 *
 * <p>The security type is a closed domain, the secured obligation must be
 * strictly positive, held collateral may be zero but never negative, and the
 * currency must be a three-letter ISO code in the contract currency. The status
 * is derived on create: a record whose expiry date is already past is recorded
 * as {@code EXPIRED}, every other record starts {@code ACTIVE}. A status action
 * can only release or expire a live record; release is terminal.</p>
 */
final class ContractSecurityRules {

    private static final int APPROVAL_REF_MAX_LENGTH = 64;

    private ContractSecurityRules() {
    }

    static ContractSecurity validate(Contract contract, CreateContractSecurityRequest request) {
        String securityType = Values.enumOf(
                SecurityRules.SecurityType.class, request.securityType(), "securityType").name();

        BigDecimal required = Values.money(request.requiredAmount(), "requiredAmount", true);
        if (required.signum() <= 0) {
            throw new ContractValidationException("INVALID_REQUIRED_AMOUNT", "requiredAmount",
                    "requiredAmount must be greater than zero");
        }

        BigDecimal held = request.heldAmount() == null
                ? BigDecimal.ZERO.setScale(4)
                : Values.money(request.heldAmount(), "heldAmount", true);

        String currency = Values.currency(request.currency(), "currency");
        if (!currency.equals(contract.currency())) {
            throw new ContractConflictException("SECURITY_CURRENCY_MISMATCH",
                    "Security currency " + currency + " does not match contract currency "
                            + contract.currency(),
                    "currency", "SPEC CTR-17 keeps security in the contract currency");
        }

        String approvalRef = approvalRef(request.approvalRef());
        LocalDate expiryDate = request.expiryDate();
        // The internal bind and commutation reads assume an effective date. A public
        // record without one starts on the contract inception, or on the expiry date
        // when that is earlier.
        LocalDate effectiveFrom = contract.effectiveDate();
        if (expiryDate != null && expiryDate.isBefore(effectiveFrom)) {
            effectiveFrom = expiryDate;
        }
        String status = expiryDate != null && expiryDate.isBefore(LocalDate.now())
                ? SecurityRules.SecurityStatus.EXPIRED.name()
                : SecurityRules.SecurityStatus.ACTIVE.name();

        return ContractSecurity.create(
                contract.tenantId(), contract.id(), securityType, required, held, currency,
                request.providerPartyId(), null, effectiveFrom, null, expiryDate, status,
                null, null, null, approvalRef, request.approvedAt());
    }

    private static String approvalRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > APPROVAL_REF_MAX_LENGTH) {
            throw new ContractValidationException("INVALID_APPROVAL_REF", "approvalRef",
                    "approvalRef must be at most " + APPROVAL_REF_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    static SecurityRules.SecurityStatus currentStatus(String raw) {
        try {
            return SecurityRules.SecurityStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown stored security status: " + raw, ex);
        }
    }

    static SecurityRules.SecurityStatus parseTargetStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("INVALID_STATUS", "status", "status is required");
        }
        SecurityRules.SecurityStatus target;
        try {
            target = SecurityRules.SecurityStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("INVALID_STATUS", "status", "Unknown security status: " + raw);
        }
        if (target != SecurityRules.SecurityStatus.RELEASED
                && target != SecurityRules.SecurityStatus.EXPIRED) {
            throw invalid("INVALID_STATUS", "status",
                    "Only RELEASED and EXPIRED can be requested; " + target
                            + " is not a security status action");
        }
        return target;
    }

    /**
     * The collateral lifecycle: a live record can be released or expired, and an
     * expired record can still be released. Release is terminal, so a released
     * record rejects every further status action.
     */
    static void validateTransition(SecurityRules.SecurityStatus from,
                                   SecurityRules.SecurityStatus target) {
        boolean allowed = switch (from) {
            case ACTIVE, OUTSTANDING, PARTIAL, REQUIRED ->
                    target == SecurityRules.SecurityStatus.RELEASED
                            || target == SecurityRules.SecurityStatus.EXPIRED;
            case EXPIRED -> target == SecurityRules.SecurityStatus.RELEASED;
            case RELEASED, BREACH -> false;
        };
        if (!allowed) {
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Security status cannot move from " + from + " to " + target,
                    "status", "Allowed transitions: ACTIVE, OUTSTANDING, PARTIAL, or REQUIRED "
                    + "-> RELEASED or EXPIRED; EXPIRED -> RELEASED; RELEASED is terminal");
        }
    }

    private static ContractValidationException invalid(String code, String field, String message) {
        return new ContractValidationException(code, field, message);
    }
}
