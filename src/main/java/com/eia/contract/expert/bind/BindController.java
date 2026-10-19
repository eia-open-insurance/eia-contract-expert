package com.eia.contract.expert.bind;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal bind API (SPEC 11.3.10 bind). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}")
public class BindController {

    private final ContractBindService bindService;

    public BindController(ContractBindService bindService) {
        this.bindService = bindService;
    }

    @GetMapping("/bind-eligibility")
    public BindEligibility eligibility(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        return bindService.evaluate(tenantId, contractId);
    }

    @PostMapping("/bind")
    public ContractBindService.BindResult bind(@RequestParam UUID tenantId,
                                               @PathVariable UUID contractId,
                                               @RequestBody(required = false) BindContractRequest request) {
        return bindService.bind(tenantId, contractId, request == null ? null : request.approvedBy(),
                request == null ? 0 : request.expectedVersion());
    }

    public record BindContractRequest(UUID approvedBy, int expectedVersion) {}
}
