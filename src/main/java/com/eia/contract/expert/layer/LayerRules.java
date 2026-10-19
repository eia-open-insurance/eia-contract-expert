package com.eia.contract.expert.layer;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Layer invariants: SPEC CTR-02 (the tower has no unintended overlaps and gaps are
 * documented) and CTR-03 (an annual aggregate limit is at least the layer limit).
 */
public final class LayerRules {

    public static final List<String> LAYER_TYPES = List.of(
            "PROPORTIONAL", "EXCESS_OF_LOSS", "AGGREGATE_EXCESS", "STOP_LOSS",
            "CATASTROPHE", "CLASH", "FACULTATIVE", "TOP_AND_DROP");

    public static final List<String> LIMIT_BASES = List.of(
            "PER_OCCURRENCE", "PER_RISK", "PER_EVENT", "ANNUAL_AGGREGATE", "POLICY_YEAR", "PER_LOSS");

    public static final List<String> REINSTATEMENT_TYPES = List.of(
            "NONE", "FREE", "PAID_PRO_RATA", "PAID_FULL_RATE", "PER_LOSS");

    public static final List<String> LIVE_STATUSES = List.of("QUOTED", "BOUND", "IN_FORCE");

    private LayerRules() {
    }

    public static ContractLayer validate(Contract contract, LayerController.CreateLayerRequest request) {
        int layerNumber = Values.integer(request.layerNumber(), "layerNumber", 1, 999, true);
        String layerType = Values.enumOf(LayerType.class, request.layerType(), "layerType").name();
        BigDecimal attachment = Values.money(request.attachmentAmount(), "attachmentAmount", true);
        BigDecimal limit = Values.money(request.limitAmount(), "limitAmount", true);
        if (limit.signum() <= 0) {
            throw new ContractConflictException("INVALID_LIMIT_AMOUNT",
                    "limitAmount must be greater than zero", "limitAmount", "limitAmount must be > 0");
        }
        String currency = request.currency() == null || request.currency().isBlank()
                ? contract.currency()
                : Values.currency(request.currency(), "currency");
        if (!currency.equals(contract.currency())) {
            throw new ContractConflictException("LAYER_CURRENCY_MISMATCH",
                    "Layer currency " + currency + " does not match contract currency " + contract.currency(),
                    "currency", "A tower must be expressed in the contract currency");
        }
        String limitBasis = Values.enumOf(LimitBasis.class, request.limitBasis(), "limitBasis").name();
        BigDecimal aggregateLimit = Values.money(request.aggregateLimitAmount(), "aggregateLimitAmount", false);
        if (aggregateLimit != null && aggregateLimit.compareTo(limit) < 0) {
            throw new ContractConflictException("AGGREGATE_LIMIT_BELOW_LAYER_LIMIT",
                    "aggregateLimitAmount " + aggregateLimit.toPlainString()
                            + " is below the layer limit " + limit.toPlainString(),
                    "aggregateLimitAmount", "SPEC CTR-03 requires aggregateLimitAmount >= limitAmount");
        }
        Integer reinstatementCount = Values.integer(request.reinstatementCount(), "reinstatementCount", 0, 99, false);
        String reinstatementType = request.reinstatementType() == null || request.reinstatementType().isBlank()
                ? "NONE"
                : Values.enumOf(ReinstatementType.class, request.reinstatementType(), "reinstatementType").name();
        if (reinstatementCount != null && reinstatementCount > 0 && "NONE".equals(reinstatementType)) {
            throw new ContractConflictException("REINSTATEMENT_TYPE_REQUIRED",
                    "reinstatementCount is " + reinstatementCount + " but reinstatementType is NONE",
                    "reinstatementType", "Choose FREE, PAID_PRO_RATA, PAID_FULL_RATE or PER_LOSS");
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? contract.effectiveDate() : request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo() == null
                ? contract.expirationDate() : request.effectiveTo();
        Values.dateRange(effectiveFrom, effectiveTo, "effective");
        if (effectiveFrom.isBefore(contract.effectiveDate())
                || effectiveTo.isAfter(contract.expirationDate())) {
            throw new ContractConflictException("LAYER_OUTSIDE_CONTRACT_TERM",
                    "Layer " + effectiveFrom + ".." + effectiveTo + " is outside contract term "
                            + contract.effectiveDate() + ".." + contract.expirationDate(),
                    "effectiveFrom", "A layer must sit inside the contract period");
        }
        String status = request.status() == null || request.status().isBlank()
                ? "QUOTED"
                : Values.enumOf(LayerStatus.class, request.status(), "status").name();
        String note = Values.optionalText(request.note(), 300);
        return ContractLayer.create(contract.tenantId(), contract.id(), layerNumber, layerType,
                attachment, limit, currency, limitBasis, aggregateLimit, reinstatementCount,
                reinstatementType, note, effectiveFrom, effectiveTo, status);
    }

    /**
     * Rejects an overlapping tower. Adjacent layers (next attachment equal to the
     * previous top) are allowed; a gap is allowed but the caller can report it.
     */
    public static void checkTower(List<ContractLayer> liveLayers, ContractLayer candidate) {
        List<ContractLayer> tower = new ArrayList<>(liveLayers);
        tower.add(candidate);
        checkTower(tower);
    }

    public static void checkTower(List<ContractLayer> liveLayers) {
        List<ContractLayer> tower = new ArrayList<>(liveLayers);
        tower.sort(Comparator.comparing(ContractLayer::attachmentAmount)
                .thenComparing(ContractLayer::layerNumber));
        for (int i = 1; i < tower.size(); i++) {
            ContractLayer below = tower.get(i - 1);
            ContractLayer above = tower.get(i);
            if (above.attachmentAmount().compareTo(below.top()) < 0) {
                throw new ContractConflictException("LAYER_OVERLAP",
                        "Layer " + above.layerNumber() + " attaches at "
                                + above.attachmentAmount().toPlainString() + ", below the top of layer "
                                + below.layerNumber() + " at " + below.top().toPlainString(),
                        "attachmentAmount", "SPEC CTR-02 forbids overlapping layers");
            }
        }
    }

    /** Gaps in the tower, reported to the caller so they can be documented (CTR-02). */
    public static List<String> gaps(List<ContractLayer> liveLayers) {
        List<ContractLayer> tower = new ArrayList<>(liveLayers);
        tower.sort(Comparator.comparing(ContractLayer::attachmentAmount)
                .thenComparing(ContractLayer::layerNumber));
        List<String> gaps = new ArrayList<>();
        for (int i = 1; i < tower.size(); i++) {
            ContractLayer below = tower.get(i - 1);
            ContractLayer above = tower.get(i);
            BigDecimal top = below.top();
            if (above.attachmentAmount().compareTo(top) > 0) {
                gaps.add("Gap between layer " + below.layerNumber() + " top "
                        + top.toPlainString() + " and layer " + above.layerNumber()
                        + " attachment " + above.attachmentAmount().toPlainString());
            }
        }
        return gaps;
    }

    public enum LayerType {
        PROPORTIONAL, EXCESS_OF_LOSS, AGGREGATE_EXCESS, STOP_LOSS, CATASTROPHE,
        CLASH, FACULTATIVE, TOP_AND_DROP
    }

    public enum LimitBasis {
        PER_OCCURRENCE, PER_RISK, PER_EVENT, ANNUAL_AGGREGATE, POLICY_YEAR, PER_LOSS
    }

    public enum ReinstatementType {
        NONE, FREE, PAID_PRO_RATA, PAID_FULL_RATE, PER_LOSS
    }

    public enum LayerStatus {
        QUOTED, BOUND, IN_FORCE, EXHAUSTED, EXPIRED, CANCELLED
    }
}
