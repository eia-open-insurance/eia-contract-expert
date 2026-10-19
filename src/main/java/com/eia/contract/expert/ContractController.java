package com.eia.contract.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Internal write API consumed by the Contract domain service. */
@RestController
@RequestMapping("/internal/v1/contracts")
public class ContractController {

    private final ContractRepository repository;

    public ContractController(ContractRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Contract> list(@RequestParam UUID tenantId) {
        return repository.findAll(tenantId);
    }

    @GetMapping("/{id}")
    public Contract get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new ContractNotFoundException(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Contract create(@Valid @RequestBody CreateContractRequest request) {
        ContractType contractType = ContractRules.parseContractType(request.contractType());
        ContractRules.validateDateRange(request.effectiveDate(), request.expirationDate());
        ContractStatus status = ContractRules.resolveInitialStatus(request.status());
        return repository.create(request, contractType, status);
    }

    @PatchMapping("/{id}/status")
    public Contract updateStatus(@RequestParam UUID tenantId,
                                 @PathVariable UUID id,
                                 @Valid @RequestBody UpdateStatusRequest request) {
        Contract current = repository.findById(tenantId, id)
                .orElseThrow(() -> new ContractNotFoundException(id));
        ContractStatus from = ContractRules.parseStatus(current.status());
        ContractStatus target = ContractRules.parseStatus(request.status());
        if (target == ContractStatus.BOUND) {
            throw new ContractConflictException("BIND_REQUIRES_COMMAND",
                    "Bind is a command: POST /internal/v1/contracts/" + id + "/bind enforces the "
                            + "tower, share, term, security and approval preconditions",
                    "status", "PLACED -> BOUND is only available through the bind command");
        }
        ContractRules.validateTransition(from, target);
        return repository.updateStatus(tenantId, id, target, request.expectedVersion());
    }
}
