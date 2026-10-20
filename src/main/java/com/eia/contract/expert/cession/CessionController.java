package com.eia.contract.expert.cession;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractEvents;
import com.eia.contract.expert.ContractGuard;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Values;
import com.eia.contract.expert.bordereau.BordereauHeaderRepository;
import com.eia.contract.expert.layer.ContractLayer;
import com.eia.contract.expert.layer.LayerRepository;
import com.eia.contract.expert.security.SecurityRepository;
import com.eia.contract.expert.security.SecurityRules;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Internal cession API (SPEC 11.3.10 cessions). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/cessions")
public class CessionController {

    private final ContractRepository contracts;
    private final CessionRepository cessions;
    private final LayerRepository layers;
    private final SecurityRepository securities;
    private final BordereauHeaderRepository bordereaux;
    private final BusinessKeys keys;
    private final OutboxRepository outbox;

    public CessionController(ContractRepository contracts, CessionRepository cessions,
                             LayerRepository layers, SecurityRepository securities,
                             BordereauHeaderRepository bordereaux, BusinessKeys keys,
                             OutboxRepository outbox) {
        this.contracts = contracts;
        this.cessions = cessions;
        this.layers = layers;
        this.securities = securities;
        this.bordereaux = bordereaux;
        this.keys = keys;
        this.outbox = outbox;
    }

    @GetMapping
    public List<ContractCession> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return cessions.findAll(tenantId, contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractCession create(@RequestParam UUID tenantId,
                                  @PathVariable UUID contractId,
                                  @RequestBody CreateCessionRequest request) {
        Contract contract = requireContract(tenantId, contractId);
        ContractGuard.requireCessionAllowed(contract);
        SecurityRules.checkNoBreach(securities.findAll(tenantId, contractId));
        if (request.layerId() != null) {
            layers.get(tenantId, contractId, request.layerId());
        }
        if (request.bordereauId() != null) {
            bordereaux.get(tenantId, contractId, request.bordereauId());
        }
        int year = sourceYear(contract, request);
        String cessionNumber = request.cessionNumber() == null || request.cessionNumber().isBlank()
                ? keys.next(tenantId, "CES", year)
                : Values.text(request.cessionNumber(), "cessionNumber", 32);
        ContractCession cession = CessionRules.validate(contract, request, cessionNumber);
        List<ContractLayer> live = layers.findLive(tenantId, contractId);
        List<ContractCession> existing = cessions.findAll(tenantId, contractId);
        CessionRules.checkCapacity(live, existing, cession);
        BigDecimal existingShare = cessions.activeShareForSource(tenantId, contractId,
                cession.sourceType(), cession.policyRef(), cession.claimRef(), cession.treatyYear());
        CessionRules.checkSourceShare(existingShare, cession.cessionPct());
        ContractCession created = cessions.create(cession);
        BigDecimal contractLimit = live.isEmpty() ? null : live.stream()
                .map(ContractLayer::capacity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        outbox.insert(OutboxEvent.fromEnvelope(
                ContractEvents.cessionRecorded(contract, created, contractLimit, Instant.now()),
                contract.id().toString()));
        return created;
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    private static int sourceYear(Contract contract, CreateCessionRequest request) {
        if (request.treatyYear() != null && request.treatyYear() >= 1900 && request.treatyYear() <= 2100) {
            return request.treatyYear();
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? contract.effectiveDate() : request.effectiveFrom();
        return effectiveFrom.getYear();
    }

    public record CreateCessionRequest(
            UUID layerId,
            String cessionNumber,
            String sourceVertical,
            String sourceType,
            String policyRef,
            String claimRef,
            String cessionType,
            BigDecimal cessionPct,
            BigDecimal cededPremiumAmount,
            BigDecimal cededLossAmount,
            String currency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Integer treatyYear,
            String status,
            UUID bordereauId
    ) {}
}
