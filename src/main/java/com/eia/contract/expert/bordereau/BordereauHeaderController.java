package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;
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
import java.util.Locale;
import java.util.UUID;

/**
 * Legacy internal bordereau-ingest API (SPEC 11.3.10 bordereaux).
 *
 * <p>This slice records the header of a source document received from the
 * cedent: bordereau type, source format, period, line counts, and declared
 * total. The period-bordereau lifecycle that creates technical accounts is
 * {@link BordereauController}, exposed under {@code /api/v1}. Both slices share
 * the {@code contract_bordereau} table; the financial slice owns rows with
 * {@code period_start} set, and this one owns the remaining ingest rows.</p>
 */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/bordereaux")
public class BordereauHeaderController {

    private static final List<String> BORDEREAU_TYPES = List.of(
            "PREMIUM", "CLAIMS", "CESSION", "ACCOUNTING", "LOSS_DEVELOPMENT", "SECURITY", "CASH_LOSS");

    private static final List<String> SOURCE_FORMATS = List.of("CSV", "XLSX", "XML", "JSON", "MANUAL");

    private static final List<String> BORDEREAU_STATUSES = List.of(
            "RECEIVED", "PARSING", "VALIDATED", "PARTIALLY_POSTED", "POSTED", "REJECTED", "SUPERSEDED");

    private final ContractRepository contracts;
    private final BordereauHeaderRepository bordereaux;
    private final BusinessKeys keys;

    public BordereauHeaderController(ContractRepository contracts,
                                     BordereauHeaderRepository bordereaux,
                                     BusinessKeys keys) {
        this.contracts = contracts;
        this.bordereaux = bordereaux;
        this.keys = keys;
    }

    @GetMapping
    public List<ContractBordereauHeader> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return bordereaux.findAll(tenantId, contractId);
    }

    @GetMapping("/{bordereauId}")
    public ContractBordereauHeader get(@RequestParam UUID tenantId, @PathVariable UUID contractId,
                                       @PathVariable UUID bordereauId) {
        requireContract(tenantId, contractId);
        return bordereaux.get(tenantId, contractId, bordereauId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractBordereauHeader create(@RequestParam UUID tenantId,
                                          @PathVariable UUID contractId,
                                          @RequestBody CreateBordereauHeaderRequest request) {
        Contract contract = requireContract(tenantId, contractId);
        String bordereauType = Values.enumOf(BordereauType.class, request.bordereauType(),
                "bordereauType").name();
        LocalDate periodFrom = request.periodFrom();
        LocalDate periodTo = request.periodTo();
        Values.dateRange(periodFrom, periodTo, "period");
        String sourceFormat = Values.enumOf(SourceFormat.class, request.sourceFormat(),
                "sourceFormat").name();
        Instant receivedAt = request.receivedAt() == null ? Instant.now() : request.receivedAt();
        int lineCount = Values.integer(request.lineCount(), "lineCount", 0, 10_000_000, false) == null
                ? 0 : request.lineCount();
        Integer validLineCount = Values.integer(request.validLineCount(), "validLineCount",
                0, 10_000_000, false);
        if (validLineCount != null && validLineCount > lineCount) {
            throw new ContractConflictException("INVALID_LINE_COUNT",
                    "validLineCount " + validLineCount + " exceeds lineCount " + lineCount,
                    "validLineCount", "A bordereau cannot have more valid lines than lines");
        }
        Integer exceptionCount = Values.integer(request.exceptionCount(), "exceptionCount",
                0, 10_000_000, false);
        BigDecimal totalAmount = Values.money(request.totalAmount(), "totalAmount", false);
        String currency = null;
        if (totalAmount != null) {
            currency = request.currency() == null || request.currency().isBlank()
                    ? contract.currency()
                    : Values.currency(request.currency(), "currency");
            if (!currency.equals(contract.currency())) {
                throw new ContractConflictException("BORDEREAU_CURRENCY_MISMATCH",
                        "Bordereau currency " + currency + " does not match contract currency "
                                + contract.currency(),
                        "currency", "SPEC CTR-17 keeps bordereau totals in the contract currency");
            }
        }
        String status = request.status() == null || request.status().isBlank()
                ? "RECEIVED"
                : Values.enumOf(BordereauStatus.class, request.status(), "status").name();
        String bordereauNumber = request.bordereauNumber() == null || request.bordereauNumber().isBlank()
                ? keys.next(tenantId, "BRD", periodFrom.getYear())
                : Values.text(request.bordereauNumber(), "bordereauNumber", 32);
        String sourceReference = Values.optionalText(request.sourceReference(), 128);
        String checksum = Values.optionalText(request.checksum(), 64);
        String notes = Values.optionalText(request.notes(), 400);
        return bordereaux.create(ContractBordereauHeader.create(tenantId, contractId, bordereauNumber,
                bordereauType, periodFrom, periodTo, receivedAt, request.sourceDocumentId(),
                sourceFormat, sourceReference, status, lineCount, validLineCount, exceptionCount,
                totalAmount, currency, checksum, notes));
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public record CreateBordereauHeaderRequest(
            String bordereauNumber,
            String bordereauType,
            LocalDate periodFrom,
            LocalDate periodTo,
            Instant receivedAt,
            UUID sourceDocumentId,
            String sourceFormat,
            String sourceReference,
            String status,
            Integer lineCount,
            Integer validLineCount,
            Integer exceptionCount,
            BigDecimal totalAmount,
            String currency,
            String checksum,
            String notes
    ) {}

    public enum BordereauType {
        PREMIUM, CLAIMS, CESSION, ACCOUNTING, LOSS_DEVELOPMENT, SECURITY, CASH_LOSS
    }

    public enum SourceFormat {
        CSV, XLSX, XML, JSON, MANUAL
    }

    public enum BordereauStatus {
        RECEIVED, PARSING, VALIDATED, PARTIALLY_POSTED, POSTED, REJECTED, SUPERSEDED
    }
}
