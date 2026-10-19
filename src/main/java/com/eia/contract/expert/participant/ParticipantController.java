package com.eia.contract.expert.participant;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractGuard;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.layer.LayerRepository;
import org.springframework.http.HttpStatus;
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

/** Internal participant API (SPEC 11.3.10 participants). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/participants")
public class ParticipantController {

    private final ContractRepository contracts;
    private final ParticipantRepository participants;
    private final LayerRepository layers;

    public ParticipantController(ContractRepository contracts, ParticipantRepository participants,
                                 LayerRepository layers) {
        this.contracts = contracts;
        this.participants = participants;
        this.layers = layers;
    }

    @GetMapping
    public List<ContractParticipant> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return participants.findAll(tenantId, contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractParticipant create(@RequestParam UUID tenantId,
                                      @PathVariable UUID contractId,
                                      @RequestBody CreateParticipantRequest request) {
        Contract contract = requireContract(tenantId, contractId);
        ContractGuard.requireEditable(contract);
        if (request.layerId() != null) {
            layers.get(tenantId, contractId, request.layerId());
        }
        ContractParticipant participant = ParticipantRules.validate(contract, request);
        if (ParticipantRules.isRiskBearing(participant.participantRole())) {
            BigDecimal existing = participants.activeShareTotal(
                    tenantId, contractId, participant.layerId());
            ParticipantRules.checkShareCapacity(participant.participantRole(), existing,
                    participant.sharePct());
        }
        return participants.create(participant);
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public record CreateParticipantRequest(
            UUID layerId,
            UUID partyId,
            String participantRole,
            BigDecimal sharePct,
            BigDecimal signedLinePct,
            Integer orderNumber,
            Boolean leadFlag,
            BigDecimal capacityAmount,
            String securityRating,
            Boolean collateralRequired,
            String signatureStatus,
            Instant signedAt,
            String authorizationRef,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status
    ) {}
}
