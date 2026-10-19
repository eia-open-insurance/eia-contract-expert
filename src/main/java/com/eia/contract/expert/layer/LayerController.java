package com.eia.contract.expert.layer;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Internal layer API (SPEC 11.3.10 layers). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/layers")
public class LayerController {

    private final ContractRepository contracts;
    private final LayerRepository layers;

    public LayerController(ContractRepository contracts, LayerRepository layers) {
        this.contracts = contracts;
        this.layers = layers;
    }

    @GetMapping
    public List<ContractLayer> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return layers.findAll(tenantId, contractId);
    }

    @GetMapping("/summary")
    public TowerSummary summary(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        List<ContractLayer> live = layers.findLive(tenantId, contractId);
        return new TowerSummary(contractId, live.size(), layers.capacity(tenantId, contractId),
                LayerRules.gaps(live));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractLayer create(@RequestParam UUID tenantId,
                                @PathVariable UUID contractId,
                                @RequestBody CreateLayerRequest request) {
        Contract contract = requireContract(tenantId, contractId);
        ContractGuard.requireEditable(contract);
        ContractLayer layer = LayerRules.validate(contract, request);
        LayerRules.checkTower(layers.findLive(tenantId, contractId), layer);
        return layers.create(layer);
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    /**
     * Layer create payload. Every field is validated with a structured error code;
     * {@code currency} defaults to the contract currency and the effective dates
     * default to the contract term.
     */
    public record CreateLayerRequest(
            Integer layerNumber,
            String layerType,
            BigDecimal attachmentAmount,
            BigDecimal limitAmount,
            String currency,
            String limitBasis,
            BigDecimal aggregateLimitAmount,
            Integer reinstatementCount,
            String reinstatementType,
            String note,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status
    ) {}

    /** Tower position: size, aggregate capacity and documented-gap candidates. */
    public record TowerSummary(UUID contractId, int layerCount, BigDecimal capacity, List<String> gaps) {}
}
