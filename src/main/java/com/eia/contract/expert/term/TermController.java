package com.eia.contract.expert.term;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractGuard;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Internal terms API (SPEC 11.3.10 terms). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/terms")
public class TermController {

    private final ContractRepository contracts;
    private final TermRepository terms;

    public TermController(ContractRepository contracts, TermRepository terms) {
        this.contracts = contracts;
        this.terms = terms;
    }

    @GetMapping
    public List<ContractTerm> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return terms.findAll(tenantId, contractId);
    }

    @GetMapping("/requirements")
    public TermRequirements requirements(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        Contract contract = requireContract(tenantId, contractId);
        List<String> required = RequiredTerms.forType(contract.contractType()).stream()
                .map(Enum::name).toList();
        List<String> missing = RequiredTerms.missing(
                contract.contractType(), terms.codes(tenantId, contractId)).stream()
                .map(Enum::name).toList();
        return new TermRequirements(contract.contractType(), required, missing);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractTerm create(@RequestParam UUID tenantId,
                               @PathVariable UUID contractId,
                               @RequestBody CreateTermRequest request) {
        Contract contract = requireContract(tenantId, contractId);
        ContractGuard.requireEditable(contract);
        return terms.create(TermRules.validate(contract, request));
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public record CreateTermRequest(
            String termCode,
            String termValue,
            String valueType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceClause
    ) {}

    /** Required terms for the contract type and which of them are still missing. */
    public record TermRequirements(String contractType, List<String> required, List<String> missing) {}
}
