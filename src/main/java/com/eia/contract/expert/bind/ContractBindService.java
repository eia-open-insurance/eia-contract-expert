package com.eia.contract.expert.bind;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractEvents;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.layer.ContractLayer;
import com.eia.contract.expert.layer.LayerRepository;
import com.eia.contract.expert.layer.LayerRules;
import com.eia.contract.expert.participant.ContractParticipant;
import com.eia.contract.expert.participant.ParticipantRepository;
import com.eia.contract.expert.participant.ParticipantRules;
import com.eia.contract.expert.security.ContractSecurity;
import com.eia.contract.expert.security.SecurityRepository;
import com.eia.contract.expert.security.SecurityRules;
import com.eia.contract.expert.term.RequiredTerms;
import com.eia.contract.expert.term.TermCode;
import com.eia.contract.expert.term.TermRepository;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates and applies the bind preconditions (SPEC 11.3.8 `contract.bind`).
 *
 * <p>Checks: the contract is PLACED; the layer tower is coherent (CTR-02); the
 * participant shares total 100% (CTR-01); every term required by the contract type
 * is recorded; and no security is in breach (CTR-11). Binding requires an approver
 * (CTR-15) and is written atomically with the checks.</p>
 */
@Service
public class ContractBindService {

    private final ContractRepository contracts;
    private final LayerRepository layers;
    private final ParticipantRepository participants;
    private final TermRepository terms;
    private final SecurityRepository securities;
    private final OutboxRepository outbox;

    public ContractBindService(ContractRepository contracts, LayerRepository layers,
                               ParticipantRepository participants, TermRepository terms,
                               SecurityRepository securities, OutboxRepository outbox) {
        this.contracts = contracts;
        this.layers = layers;
        this.participants = participants;
        this.terms = terms;
        this.securities = securities;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public BindEligibility evaluate(UUID tenantId, UUID contractId) {
        Contract contract = contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        return evaluate(contract);
    }

    @Transactional
    public BindResult bind(UUID tenantId, UUID contractId, UUID approvedBy, int expectedVersion) {
        Contract contract = contracts.findByIdForUpdate(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        if (approvedBy == null) {
            throw new ContractValidationException("BIND_APPROVAL_REQUIRED", "approvedBy",
                    "Bind requires an underwriter approval (SPEC CTR-15)");
        }
        BindEligibility eligibility = evaluate(contract);
        List<BindCheck> failed = eligibility.checks().stream()
                .filter(check -> !check.passed())
                .toList();
        if (!failed.isEmpty()) {
            throw new BindPreconditionFailedException(contractId, contract.status(), failed);
        }
        Contract bound = contracts.bind(tenantId, contractId, approvedBy, expectedVersion);
        publish(ContractEvents.statusChanged(
                contract, bound, layers.capacity(tenantId, contractId), Instant.now()));
        return new BindResult(bound, eligibility);
    }

    private void publish(List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            outbox.insert(OutboxEvent.fromEnvelope(event));
        }
    }

    private BindEligibility evaluate(Contract contract) {
        List<ContractLayer> liveLayers = layers.findLive(contract.tenantId(), contract.id());
        List<ParticipantRepository.ShareTotal> totals =
                participants.activeShareTotals(contract.tenantId(), contract.id());
        Set<String> termCodes = terms.codes(contract.tenantId(), contract.id());
        List<ContractSecurity> securityRows =
                securities.findAll(contract.tenantId(), contract.id());

        List<BindCheck> checks = List.of(
                placed(contract),
                tower(liveLayers),
                shares(liveLayers, totals),
                requiredTerms(contract, termCodes),
                security(securityRows));
        boolean bindable = checks.stream().allMatch(BindCheck::passed);
        return new BindEligibility(contract.id(), contract.contractNumber(), contract.status(),
                bindable, checks);
    }

    private static BindCheck placed(Contract contract) {
        if ("PLACED".equals(contract.status())) {
            return BindCheck.pass("CONTRACT_PLACED", "Contract is PLACED");
        }
        return BindCheck.fail("CONTRACT_PLACED",
                "Contract is " + contract.status() + "; bind requires PLACED");
    }

    private static BindCheck tower(List<ContractLayer> liveLayers) {
        try {
            LayerRules.checkTower(liveLayers);
        } catch (ContractConflictException ex) {
            return BindCheck.fail("LAYERS_COHERENT", ex.getMessage());
        }
        if (liveLayers.isEmpty()) {
            return BindCheck.pass("LAYERS_COHERENT",
                    "No layer tower recorded; participations are contract level");
        }
        BigDecimal capacity = liveLayers.stream().map(ContractLayer::capacity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> gaps = LayerRules.gaps(liveLayers);
        String detail = liveLayers.size() + " layers, capacity " + capacity.toPlainString()
                + (gaps.isEmpty() ? ", no gaps" : ", gaps: " + String.join("; ", gaps));
        return BindCheck.pass("LAYERS_COHERENT", detail);
    }

    /**
     * CTR-01. When every participation is recorded at contract level, the contract
     * total is the band that must reach 100%; as soon as at least one participation
     * names a layer, every live layer must be balanced on its own.
     */
    private static BindCheck shares(List<ContractLayer> liveLayers,
                                    List<ParticipantRepository.ShareTotal> totals) {
        if (totals.isEmpty()) {
            return BindCheck.fail("SHARES_BALANCED", "No active risk-bearing participant is recorded");
        }
        boolean layerLevel = totals.stream().anyMatch(total -> total.layerId() != null);
        Map<UUID, BigDecimal> byLayer = new LinkedHashMap<>();
        for (ParticipantRepository.ShareTotal total : totals) {
            byLayer.merge(total.layerId(), total.total(), BigDecimal::add);
        }
        List<String> problems = new ArrayList<>();
        if (!layerLevel) {
            BigDecimal overall = byLayer.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!ParticipantRules.isBalanced(overall)) {
                problems.add("contract shares total " + overall.toPlainString() + "%");
            }
        } else {
            Set<UUID> layerIds = new LinkedHashSet<>();
            for (ContractLayer layer : liveLayers) {
                layerIds.add(layer.id());
            }
            for (UUID layerId : layerIds) {
                BigDecimal total = byLayer.getOrDefault(layerId, BigDecimal.ZERO);
                if (!ParticipantRules.isBalanced(total)) {
                    String label = liveLayers.stream()
                            .filter(layer -> layer.id().equals(layerId))
                            .map(layer -> "layer " + layer.layerNumber())
                            .findFirst().orElse("layer " + layerId);
                    problems.add(label + " shares total " + total.toPlainString() + "%");
                }
            }
        }
        if (!problems.isEmpty()) {
            return BindCheck.fail("SHARES_BALANCED",
                    String.join("; ", problems) + " (must total 100 +/- 0.0001)");
        }
        if (layerLevel) {
            return BindCheck.pass("SHARES_BALANCED", "Every layer totals 100%");
        }
        return BindCheck.pass("SHARES_BALANCED", "Contract participations total 100%");
    }

    private static BindCheck requiredTerms(Contract contract, Set<String> termCodes) {
        Set<TermCode> missing = RequiredTerms.missing(contract.contractType(), termCodes);
        if (missing.isEmpty()) {
            return BindCheck.pass("REQUIRED_TERMS_PRESENT",
                    "All required terms for " + contract.contractType() + " are recorded");
        }
        return BindCheck.fail("REQUIRED_TERMS_PRESENT",
                "Missing terms for " + contract.contractType() + ": " + missing);
    }

    private static BindCheck security(List<ContractSecurity> securityRows) {
        try {
            SecurityRules.checkNoBreach(securityRows);
        } catch (ContractConflictException ex) {
            return BindCheck.fail("SECURITY_NOT_BREACHED", ex.getMessage());
        }
        long outstanding = securityRows.stream()
                .filter(row -> !"RELEASED".equals(row.status()) && !"EXPIRED".equals(row.status()))
                .count();
        return BindCheck.pass("SECURITY_NOT_BREACHED",
                outstanding + " active security record(s), none in breach");
    }

    /** Bound contract plus the eligibility snapshot that authorised it. */
    public record BindResult(Contract contract, BindEligibility eligibility) {}
}
