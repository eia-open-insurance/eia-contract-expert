package com.eia.contract.expert;

import com.eia.contract.expert.bordereau.ContractBordereau;
import com.eia.contract.expert.bordereau.ContractBordereauHeader;
import com.eia.contract.expert.cashcall.ContractCashCall;
import com.eia.contract.expert.cession.ContractCession;
import com.eia.contract.expert.commutation.ContractCommutation;
import com.eia.contract.expert.layer.ContractLayer;
import com.eia.contract.expert.participant.ContractParticipant;
import com.eia.contract.expert.security.ContractSecurity;
import com.eia.contract.expert.technicalaccount.ContractTechnicalAccount;
import com.eia.contract.expert.technicalaccount.ContractTechnicalAccountLine;
import com.eia.contract.expert.technicalaccount.TechnicalAccountLineType;
import com.eia.contract.expert.term.ContractTerm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Shared, deterministic test data for the Contract expert tests. */
public final class Fixtures {

    public static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    public static final UUID CONTRACT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    public static final UUID CEDENT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    public static final UUID REINSURER = UUID.fromString("44444444-4444-7444-8444-444444444444");
    public static final UUID LAYER_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    public static final UUID APPROVER = UUID.fromString("66666666-6666-7666-8666-666666666666");
    public static final UUID DOCUMENT = UUID.fromString("77777777-7777-7777-8777-777777777777");
    public static final UUID ACCOUNT_ID = UUID.fromString("88888888-8888-7888-8888-888888888888");
    public static final UUID LINE_ID = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");
    public static final UUID BORDEREAU_ID = UUID.fromString("99999999-9999-7999-8999-999999999998");
    public static final UUID CASH_CALL_ID = UUID.fromString("bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb");
    public static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    public static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    public static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private Fixtures() {
    }

    public static Contract contract(String status, int version) {
        return new Contract(CONTRACT_ID, TENANT, "XOL-2026-00091", "EXCESS_OF_LOSS", CEDENT, REINSURER,
                status, FROM, TO, "USD", version, NOW, NOW, null, null, null, null);
    }

    public static Contract boundContract() {
        return new Contract(CONTRACT_ID, TENANT, "XOL-2026-00091", "EXCESS_OF_LOSS", CEDENT, REINSURER,
                "BOUND", FROM, TO, "USD", 5, NOW, NOW, NOW, APPROVER, null, null);
    }

    public static ContractLayer layer(int number, String attachment, String limit) {
        return ContractLayer.create(TENANT, CONTRACT_ID, number, "EXCESS_OF_LOSS",
                new BigDecimal(attachment), new BigDecimal(limit), "USD", "PER_OCCURRENCE",
                null, null, "NONE", null, FROM, TO, "QUOTED");
    }

    public static ContractLayer layer(UUID id, int number, String attachment, String limit) {
        ContractLayer layer = layer(number, attachment, limit);
        return new ContractLayer(id, layer.tenantId(), layer.contractId(), layer.sectionId(),
                layer.layerNumber(), layer.layerType(), layer.attachmentAmount(), layer.limitAmount(),
                layer.currency(), layer.limitBasis(), layer.aggregateLimitAmount(),
                layer.reinstatementCount(), layer.reinstatementType(), layer.note(),
                layer.effectiveFrom(), layer.effectiveTo(), layer.status(),
                layer.createdAt(), layer.updatedAt());
    }

    public static ContractParticipant participant(String role, String share, UUID layerId) {
        return ContractParticipant.create(TENANT, CONTRACT_ID, layerId, REINSURER, role,
                new BigDecimal(share), null, 1, false, null, "A+", false, "PENDING",
                null, null, FROM, null, "ACTIVE");
    }

    public static ContractTerm term(String code, String value, String valueType) {
        return ContractTerm.create(TENANT, CONTRACT_ID, code, value, valueType, FROM, null, null);
    }

    public static ContractSecurity security(String required, String held, String status) {
        return ContractSecurity.create(TENANT, CONTRACT_ID, "LETTER_OF_CREDIT",
                new BigDecimal(required), new BigDecimal(held), "USD", REINSURER, CEDENT,
                FROM, null, TO, status, "CASH", null, "Release on expiry", null, null);
    }

    public static ContractCession cession(String loss) {
        return cession(loss, null);
    }

    public static ContractCession cession(String loss, UUID layerId) {
        return ContractCession.create(TENANT, CONTRACT_ID, layerId, "CES-2026-00001", "ASSUMED", "CLAIM",
                "POL-2026-0001", "CLM-2026-0007", "QUOTA_SHARE", new BigDecimal("40"),
                new BigDecimal("400000"), new BigDecimal(loss), "USD", FROM, null, 2026,
                "RECORDED", null);
    }

    public static ContractBordereauHeader bordereauHeader() {
        return ContractBordereauHeader.create(TENANT, CONTRACT_ID, "BRD-2026-00001", "CLAIMS", FROM,
                LocalDate.of(2026, 3, 31), NOW, null, "CSV", "cedent-file-1", "RECEIVED",
                10, 9, 1, new BigDecimal("240000"), "USD", null, null);
    }

    public static ContractBordereau bordereau(String status, UUID technicalAccountId) {
        return new ContractBordereau(BORDEREAU_ID, TENANT, CONTRACT_ID, "BRD-2026-00001",
                FROM, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 15), "USD",
                new BigDecimal("1000.0000"), new BigDecimal("400.0000"),
                new BigDecimal("100.0000"), new BigDecimal("50.0000"), 10, null, status,
                technicalAccountId, NOW);
    }

    public static ContractBordereau bordereau(String status) {
        return bordereau(status, null);
    }

    public static ContractTechnicalAccount technicalAccount(String status) {
        return new ContractTechnicalAccount(ACCOUNT_ID, TENANT, CONTRACT_ID, "TA-2026-00001",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), "USD",
                new BigDecimal("1000.0000"), new BigDecimal("400.0000"),
                new BigDecimal("100.0000"), new BigDecimal("50.0000"),
                new BigDecimal("550.0000"), status, NOW);
    }

    public static ContractTechnicalAccountLine technicalAccountLine(String lineType, String amount) {
        return technicalAccountLine(LINE_ID, lineType, amount);
    }

    public static ContractTechnicalAccountLine technicalAccountLine(
            UUID id, String lineType, String amount) {
        return new ContractTechnicalAccountLine(id, TENANT, ACCOUNT_ID,
                TechnicalAccountLineType.valueOf(lineType), "REF-2026-0001", "Test line",
                new BigDecimal(amount), NOW);
    }

    public static ContractCashCall cashCall(String status) {
        return cashCall(status, null);
    }

    public static ContractCashCall cashCall(String status, String settlementReference) {
        return new ContractCashCall(CASH_CALL_ID, TENANT, CONTRACT_ID, "CC-2026-00001",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                new BigDecimal("250000.0000"), "USD", LocalDate.of(2026, 4, 15),
                status, "Q1 cash call", settlementReference, NOW);
    }

    public static ContractCommutation commutation(String status, String type) {
        return new ContractCommutation(UUID.randomUUID(), TENANT, CONTRACT_ID, "COM-2026-00001",
                type, TO, LocalDate.of(2026, 11, 30), new BigDecimal("2650000"), "USD",
                "NEGOTIATED", status, status.equals("EXECUTED") ? DOCUMENT : null, null, null,
                new BigDecimal("230000"),
                status.equals("PROPOSED") ? null : APPROVER,
                status.equals("PROPOSED") ? null : NOW,
                status.equals("EXECUTED") ? NOW : null, NOW, NOW);
    }
}
