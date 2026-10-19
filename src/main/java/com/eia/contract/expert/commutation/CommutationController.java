package com.eia.contract.expert.commutation;

import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Internal commutation API (SPEC 11.3.10 commute). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/commutations")
public class CommutationController {

    private final ContractRepository contracts;
    private final CommutationRepository commutations;
    private final CommutationService service;

    public CommutationController(ContractRepository contracts, CommutationRepository commutations,
                                 CommutationService service) {
        this.contracts = contracts;
        this.commutations = commutations;
        this.service = service;
    }

    @GetMapping
    public List<ContractCommutation> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return commutations.findAll(tenantId, contractId);
    }

    @GetMapping("/{commutationId}")
    public ContractCommutation get(@RequestParam UUID tenantId, @PathVariable UUID contractId,
                                   @PathVariable UUID commutationId) {
        requireContract(tenantId, contractId);
        return commutations.get(tenantId, contractId, commutationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractCommutation create(@RequestParam UUID tenantId,
                                      @PathVariable UUID contractId,
                                      @RequestBody CreateCommutationRequest request) {
        return service.create(tenantId, contractId, request);
    }

    @PostMapping("/{commutationId}/approve")
    public ContractCommutation approve(@RequestParam UUID tenantId,
                                       @PathVariable UUID contractId,
                                       @PathVariable UUID commutationId,
                                       @RequestBody ApproveCommutationRequest request) {
        return service.approve(tenantId, contractId, commutationId, request == null
                ? null : request.approvedBy());
    }

    @PostMapping("/{commutationId}/execute")
    public CommutationService.Execution execute(@RequestParam UUID tenantId,
                                                @PathVariable UUID contractId,
                                                @PathVariable UUID commutationId,
                                                @RequestBody(required = false) ExecuteCommutationRequest request) {
        return service.execute(tenantId, contractId, commutationId, request);
    }

    private void requireContract(UUID tenantId, UUID contractId) {
        contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public record CreateCommutationRequest(
            String commutationNumber,
            String commutationType,
            LocalDate effectiveDate,
            LocalDate valuationDate,
            BigDecimal agreedAmount,
            String currency,
            String basis,
            UUID releaseDocumentId,
            UUID paymentId,
            BigDecimal savingsAmount
    ) {}

    public record ApproveCommutationRequest(UUID approvedBy, String note) {}

    public record ExecuteCommutationRequest(UUID releaseDocumentId, LocalDate releaseDate) {}
}
