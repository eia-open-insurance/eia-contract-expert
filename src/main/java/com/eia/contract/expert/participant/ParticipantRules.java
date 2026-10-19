package com.eia.contract.expert.participant;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Participant invariants: SPEC CTR-01 (a layer's participant shares total 100%
 * before bind, tolerance 0.0001) and the role/share consistency that makes the
 * total meaningful.
 */
public final class ParticipantRules {

    public static final List<String> ROLES = List.of(
            "REINSURER", "CEDENT", "LEAD", "FOLLOW", "FRONTING", "RETROCESSIONAIRE", "BROKER");

    /** Roles that carry risk and therefore count towards the 100% total. */
    public static final Set<String> RISK_BEARING = Set.of(
            "REINSURER", "LEAD", "FOLLOW", "FRONTING", "RETROCESSIONAIRE");

    public static final List<String> SIGNATURE_STATUSES = List.of(
            "PENDING", "SIGNED", "DECLINED", "WITHDRAWN");

    public static final List<String> STATUSES = List.of("ACTIVE", "ENDED", "REPLACED");

    public static final BigDecimal SHARE_TOLERANCE = new BigDecimal("0.0001");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private ParticipantRules() {
    }

    public static boolean isRiskBearing(String role) {
        return role != null && RISK_BEARING.contains(role);
    }

    public static ContractParticipant validate(Contract contract, ParticipantController.CreateParticipantRequest request) {
        UUID partyId = Values.uuid(request.partyId(), "partyId", true);
        String role = Values.enumOf(ParticipantRole.class, request.participantRole(), "participantRole").name();
        BigDecimal share = Values.percent(request.sharePct(), "sharePct", true);
        BigDecimal signedLine = Values.percent(request.signedLinePct(), "signedLinePct", false);
        if (signedLine != null && signedLine.compareTo(share) > 0) {
            throw new ContractConflictException("SIGNED_LINE_EXCEEDS_SHARE",
                    "signedLinePct " + signedLine.toPlainString() + " exceeds sharePct "
                            + share.toPlainString(),
                    "signedLinePct", "A signed line cannot exceed the offered share");
        }
        Integer orderNumber = Values.integer(request.orderNumber(), "orderNumber", 1, 999, false);
        boolean leadFlag = Values.flag(request.leadFlag(), "leadFlag", false);
        if ("LEAD".equals(role) && !leadFlag) {
            throw new ContractConflictException("LEAD_FLAG_REQUIRED",
                    "A participant with role LEAD must have leadFlag=true",
                    "leadFlag", "Set leadFlag or use the REINSURER role");
        }
        BigDecimal capacity = Values.money(request.capacityAmount(), "capacityAmount", false);
        String rating = Values.optionalText(request.securityRating(), 8);
        boolean collateralRequired = Values.flag(request.collateralRequired(), "collateralRequired", false);
        String signatureStatus = request.signatureStatus() == null || request.signatureStatus().isBlank()
                ? "PENDING"
                : Values.enumOf(SignatureStatus.class, request.signatureStatus(), "signatureStatus").name();
        Instant signedAt = request.signedAt();
        if ("SIGNED".equals(signatureStatus) && signedAt == null) {
            signedAt = Instant.now();
        }
        if (!"SIGNED".equals(signatureStatus) && signedAt != null) {
            throw new ContractConflictException("SIGNED_AT_WITHOUT_SIGNATURE",
                    "signedAt is only meaningful when signatureStatus is SIGNED",
                    "signedAt", "Change signatureStatus or remove signedAt");
        }
        String authorizationRef = Values.optionalText(request.authorizationRef(), 64);
        UUID layerId = request.layerId();
        if (request.layerId() != null) {
            // Existence is checked by the controller against the layer repository.
            layerId = Values.uuid(request.layerId(), "layerId", false);
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? contract.effectiveDate() : request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo();
        Values.dateRange(effectiveFrom, effectiveTo, "effective");
        if (effectiveFrom.isBefore(contract.effectiveDate())
                || (effectiveTo != null && effectiveTo.isAfter(contract.expirationDate()))) {
            throw new ContractConflictException("PARTICIPANT_OUTSIDE_CONTRACT_TERM",
                    "Participation " + effectiveFrom + ".." + effectiveTo + " is outside contract term "
                            + contract.effectiveDate() + ".." + contract.expirationDate(),
                    "effectiveFrom", "A participation must sit inside the contract period");
        }
        String status = request.status() == null || request.status().isBlank()
                ? "ACTIVE"
                : Values.enumOf(ParticipantStatus.class, request.status(), "status").name();
        return ContractParticipant.create(contract.tenantId(), contract.id(), layerId, partyId, role,
                share, signedLine, orderNumber, leadFlag, capacity, rating, collateralRequired,
                signatureStatus, signedAt, authorizationRef, effectiveFrom, effectiveTo, status);
    }

    /**
     * Rejects a share that would push a layer above 100% (CTR-01 in the small).
     * Partial placement is allowed: anything up to 100% is accepted before bind.
     */
    public static void checkShareCapacity(String role, BigDecimal existingTotal, BigDecimal newShare) {
        if (!isRiskBearing(role)) {
            return;
        }
        BigDecimal total = existingTotal.add(newShare);
        if (total.subtract(HUNDRED).compareTo(SHARE_TOLERANCE) > 0) {
            throw new ContractConflictException("SHARES_EXCEED_100",
                    "Participant shares would total " + total.toPlainString() + "% for this layer",
                    "sharePct", "SPEC CTR-01 allows at most 100% per layer");
        }
    }

    /** A total is balanced when it is within 0.0001 of 100% (CTR-01). */
    public static boolean isBalanced(BigDecimal total) {
        return total != null && total.subtract(HUNDRED).abs().compareTo(SHARE_TOLERANCE) <= 0;
    }

    public enum ParticipantRole {
        REINSURER, CEDENT, LEAD, FOLLOW, FRONTING, RETROCESSIONAIRE, BROKER
    }

    public enum SignatureStatus {
        PENDING, SIGNED, DECLINED, WITHDRAWN
    }

    public enum ParticipantStatus {
        ACTIVE, ENDED, REPLACED
    }
}
