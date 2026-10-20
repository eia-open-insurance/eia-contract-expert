package com.eia.contract.expert;

import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal write API consumed by the Contract domain service.
 *
 * <p>Every aggregate write appends its canonical event to the transactional
 * outbox in the same transaction (SPEC 7.5.1), so a rollback leaves neither the
 * contract mutation nor the event behind. The HTTP contract is unchanged.</p>
 */
@RestController
@RequestMapping("/internal/v1/contracts")
public class ContractController {

    private final ContractRepository repository;
    private final OutboxRepository outbox;

    public ContractController(ContractRepository repository, OutboxRepository outbox) {
        this.repository = repository;
        this.outbox = outbox;
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
    @Transactional
    public Contract create(@Valid @RequestBody CreateContractRequest request) {
        ContractType contractType = ContractRules.parseContractType(request.contractType());
        ContractRules.validateDateRange(request.effectiveDate(), request.expirationDate());
        ContractStatus status = ContractRules.resolveInitialStatus(request.status());
        Contract contract = repository.create(request, contractType, status);
        publish(ContractEvents.created(contract, Instant.now()));
        return contract;
    }

    @PatchMapping("/{id}/status")
    @Transactional
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
        Contract after = repository.updateStatus(tenantId, id, target, request.expectedVersion());
        publish(ContractEvents.statusChanged(current, after, null, Instant.now()));
        return after;
    }

    private void publish(List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            outbox.insert(OutboxEvent.fromEnvelope(event));
        }
    }
}
