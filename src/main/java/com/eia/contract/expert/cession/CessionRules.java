package com.eia.contract.expert.cession;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;
import com.eia.contract.expert.layer.ContractLayer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Cession invariants: SPEC CTR-04 (a cession must not exceed the contract's
 * remaining capacity), CTR-05 (active cessions for one source must not exceed
 * 100%), CTR-17 (currency consistency), and the ASSUMED book wall.
 */
public final class CessionRules {

    public static final List<String> CESSION_TYPES = List.of(
            "QUOTA_SHARE", "SURPLUS", "FACULTATIVE", "EXCESS_OF_LOSS", "AGGREGATE", "RETROCESSION");

    public static final List<String> CESSION_STATUSES = List.of(
            "RECORDED", "CONFIRMED", "REVERSED", "DISPUTED");

    public static final List<String> SOURCE_TYPES = List.of("POLICY", "CLAIM", "LOSS_ADVICE");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CessionRules() {
    }

    public static ContractCession validate(Contract contract, CessionController.CreateCessionRequest request,
                                           String cessionNumber) {
        String sourceVertical = request.sourceVertical() == null || request.sourceVertical().isBlank()
                ? "ASSUMED"
                : request.sourceVertical().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ASSUMED".equals(sourceVertical)) {
            throw new ContractValidationException("BOOK_WALL_VIOLATION", "sourceVertical",
                    "Contract is ASSUMED book only; " + request.sourceVertical() + " is not allowed");
        }
        String sourceType = Values.enumOf(SourceType.class, request.sourceType(), "sourceType").name();
        String policyRef = Values.optionalText(request.policyRef(), 64);
        String claimRef = Values.optionalText(request.claimRef(), 64);
        switch (sourceType) {
            case "POLICY" -> {
                if (policyRef == null) {
                    throw new ContractValidationException("CES_POLICY_REF_REQUIRED", "policyRef",
                            "A POLICY cession requires policyRef");
                }
            }
            case "CLAIM" -> {
                if (claimRef == null) {
                    throw new ContractValidationException("CES_CLAIM_REF_REQUIRED", "claimRef",
                            "A CLAIM cession requires claimRef");
                }
            }
            default -> {
                if (claimRef == null && policyRef == null) {
                    throw new ContractValidationException("CES_SOURCE_REF_REQUIRED", "claimRef",
                            "A LOSS_ADVICE cession requires claimRef or policyRef");
                }
            }
        }
        String cessionType = Values.enumOf(CessionType.class, request.cessionType(), "cessionType").name();
        BigDecimal cessionPct = Values.percent(request.cessionPct(), "cessionPct", false);
        BigDecimal premium = Values.money(request.cededPremiumAmount(), "cededPremiumAmount", false);
        BigDecimal loss = Values.money(request.cededLossAmount(), "cededLossAmount", false);
        if (premium == null && loss == null) {
            throw new ContractValidationException("CES_AMOUNT_REQUIRED", "cededLossAmount",
                    "A cession requires cededPremiumAmount or cededLossAmount");
        }
        String currency = request.currency() == null || request.currency().isBlank()
                ? contract.currency()
                : Values.currency(request.currency(), "currency");
        if (!currency.equals(contract.currency())) {
            throw new ContractConflictException("CESSION_CURRENCY_MISMATCH",
                    "Cession currency " + currency + " does not match contract currency "
                            + contract.currency(),
                    "currency", "SPEC CTR-17: premium, loss and account use the contract currency");
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? contract.effectiveDate() : request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo();
        Values.dateRange(effectiveFrom, effectiveTo, "effective");
        if (effectiveFrom.isBefore(contract.effectiveDate())
                || (effectiveTo != null && effectiveTo.isAfter(contract.expirationDate()))) {
            throw new ContractConflictException("CESSION_OUTSIDE_CONTRACT_TERM",
                    "Cession " + effectiveFrom + ".." + effectiveTo + " is outside contract term "
                            + contract.effectiveDate() + ".." + contract.expirationDate(),
                    "effectiveFrom", "A cession must sit inside the contract period");
        }
        Integer treatyYear = Values.integer(request.treatyYear(), "treatyYear", 1900, 2100, false);
        if (treatyYear == null) {
            treatyYear = effectiveFrom.getYear();
        }
        String status = request.status() == null || request.status().isBlank()
                ? "RECORDED"
                : Values.enumOf(CessionStatus.class, request.status(), "status").name();
        return ContractCession.create(contract.tenantId(), contract.id(), request.layerId(),
                cessionNumber, "ASSUMED", sourceType, policyRef, claimRef, cessionType, cessionPct,
                premium, loss, currency, effectiveFrom, effectiveTo, treatyYear, status,
                request.bordereauId());
    }

    /**
     * CTR-04: the new ceded loss must fit the remaining capacity of the layer it
     * names (or of the whole live tower). A contract with no live layer has no
     * declared limit, so the check is skipped and the caller records the cession.
     */
    public static void checkCapacity(List<ContractLayer> liveLayers, List<ContractCession> existing,
                                     ContractCession candidate) {
        if (!candidate.isActive() || candidate.cededLossAmount() == null || liveLayers.isEmpty()) {
            return;
        }
        BigDecimal capacity;
        BigDecimal used;
        String scope;
        if (candidate.layerId() != null) {
            ContractLayer layer = liveLayers.stream()
                    .filter(candidateLayer -> candidateLayer.id().equals(candidate.layerId()))
                    .findFirst()
                    .orElseThrow(() -> new ContractConflictException("LAYER_NOT_ON_CONTRACT",
                            "Layer " + candidate.layerId() + " is not a live layer of this contract",
                            "layerId", "Attach the cession to a live layer or to the contract"));
            capacity = layer.capacity();
            used = existing.stream()
                    .filter(ContractCession::isActive)
                    .filter(cession -> layer.id().equals(cession.layerId()))
                    .map(ContractCession::cededLossAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            scope = "layer " + layer.layerNumber();
        } else {
            capacity = liveLayers.stream().map(ContractLayer::capacity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            used = existing.stream()
                    .filter(ContractCession::isActive)
                    .map(ContractCession::cededLossAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            scope = "the contract tower";
        }
        BigDecimal total = used.add(candidate.cededLossAmount());
        if (total.compareTo(capacity) > 0) {
            throw new ContractConflictException("CESSION_EXCEEDS_CONTRACT_LIMIT",
                    "Ceded loss " + total.toPlainString() + " exceeds " + capacity.toPlainString()
                            + " available on " + scope,
                    "cededLossAmount", "SPEC CTR-04 forbids a cession beyond available capacity");
        }
    }

    /** CTR-05: active cessions for one source must not exceed 100% in aggregate. */
    public static void checkSourceShare(BigDecimal existingPct, BigDecimal newPct) {
        if (newPct == null) {
            return;
        }
        BigDecimal total = existingPct.add(newPct);
        if (total.compareTo(HUNDRED) > 0) {
            throw new ContractConflictException("CESSION_SOURCE_OVER_CEDED",
                    "Active cessions for this source would total " + total.toPlainString() + "%",
                    "cessionPct", "SPEC CTR-05 caps active cessions at 100% per source");
        }
    }

    public enum SourceType {
        POLICY, CLAIM, LOSS_ADVICE
    }

    public enum CessionType {
        QUOTA_SHARE, SURPLUS, FACULTATIVE, EXCESS_OF_LOSS, AGGREGATE, RETROCESSION
    }

    public enum CessionStatus {
        RECORDED, CONFIRMED, REVERSED, DISPUTED
    }
}
