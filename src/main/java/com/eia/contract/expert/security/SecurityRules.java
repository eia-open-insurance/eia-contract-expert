package com.eia.contract.expert.security;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Security invariants: the amount pair must be coherent, the effective and expiry
 * dates must make sense, and collateral above the secured obligation requires an
 * approval reference.
 */
public final class SecurityRules {

    public static final List<String> SECURITY_TYPES = List.of(
            "LETTER_OF_CREDIT", "TRUST_FUND", "FUNDS_WITHHELD", "CASH_ADVANCE",
            "PARENT_GUARANTEE", "BANK_GUARANTEE", "SECURITIES");

    public static final List<String> SECURITY_STATUSES = List.of(
            "ACTIVE", "REQUIRED", "OUTSTANDING", "PARTIAL", "RELEASED", "EXPIRED", "BREACH");

    public static final List<String> COLLATERAL_QUALITIES = List.of(
            "CASH", "GOVERNMENT_SECURITIES", "BANK_GUARANTEE", "CORPORATE_GUARANTEE", "OTHER");

    private SecurityRules() {
    }

    public static ContractSecurity validate(Contract contract, SecurityController.CreateSecurityRequest request) {
        String securityType = Values.enumOf(SecurityType.class, request.securityType(), "securityType").name();
        BigDecimal required = Values.money(request.requiredAmount(), "requiredAmount", true);
        BigDecimal held = request.heldAmount() == null
                ? required
                : Values.money(request.heldAmount(), "heldAmount", true);
        String currency = request.currency() == null || request.currency().isBlank()
                ? contract.currency()
                : Values.currency(request.currency(), "currency");
        if (!currency.equals(contract.currency())) {
            throw new ContractConflictException("SECURITY_CURRENCY_MISMATCH",
                    "Security currency " + currency + " does not match contract currency "
                            + contract.currency(),
                    "currency", "SPEC CTR-17 keeps security in the contract currency");
        }
        String approvalRef = Values.optionalText(request.approvalRef(), 64);
        Instant approvedAt = request.approvedAt();
        if (held.compareTo(required) > 0) {
            if (approvalRef == null) {
                throw new ContractConflictException("COLLATERAL_EXCEEDS_OBLIGATION",
                        "Held collateral " + held.toPlainString() + " exceeds the secured obligation "
                                + required.toPlainString() + " and no approval reference was supplied",
                        "heldAmount", "Supply approvalRef and approvedAt to over-collateralise");
            }
            if (approvedAt == null) {
                throw new ContractConflictException("COLLATERAL_APPROVAL_INCOMPLETE",
                        "Over-collateralisation requires an approval timestamp as well as a reference",
                        "approvedAt", "Set approvedAt to the approval time");
            }
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? contract.effectiveDate() : request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo();
        Values.dateRange(effectiveFrom, effectiveTo, "effective");
        LocalDate expiryDate = request.expiryDate();
        if (expiryDate != null && expiryDate.isBefore(effectiveFrom)) {
            throw new ContractConflictException("INVALID_EXPIRY_DATE",
                    "expiryDate " + expiryDate + " is before effectiveFrom " + effectiveFrom,
                    "expiryDate", "Security cannot expire before it takes effect");
        }
        String status = request.status() == null || request.status().isBlank()
                ? (held.signum() == 0 ? "REQUIRED" : "OUTSTANDING")
                : Values.enumOf(SecurityStatus.class, request.status(), "status").name();
        String collateralQuality = request.collateralQuality() == null || request.collateralQuality().isBlank()
                ? null
                : Values.enumOf(CollateralQuality.class, request.collateralQuality(),
                        "collateralQuality").name();
        String releaseConditions = Values.optionalText(request.releaseConditions(), 400);
        return ContractSecurity.create(contract.tenantId(), contract.id(), securityType, required, held,
                currency, request.providerPartyId(), request.beneficiaryPartyId(),
                effectiveFrom, effectiveTo, expiryDate, status, collateralQuality,
                request.documentId(), releaseConditions, approvalRef, approvedAt);
    }

    /**
     * SPEC CTR-11: a security breach blocks new cessions. Returns the breached row
     * so the caller can name it in the error.
     */
    public static void checkNoBreach(List<ContractSecurity> securities) {
        securities.stream()
                .filter(security -> "BREACH".equals(security.status()))
                .findFirst()
                .ifPresent(security -> {
                    throw new ContractConflictException("SECURITY_BREACH",
                            "Security " + security.id() + " is in breach; new cessions are blocked",
                            "securityId", "SPEC CTR-11 blocks cessions while security is in breach");
                });
    }

    public enum SecurityType {
        LETTER_OF_CREDIT, TRUST_FUND, FUNDS_WITHHELD, CASH_ADVANCE,
        PARENT_GUARANTEE, BANK_GUARANTEE, SECURITIES
    }

    public enum SecurityStatus {
        ACTIVE, REQUIRED, OUTSTANDING, PARTIAL, RELEASED, EXPIRED, BREACH
    }

    public enum CollateralQuality {
        CASH, GOVERNMENT_SECURITIES, BANK_GUARANTEE, CORPORATE_GUARANTEE, OTHER
    }
}
