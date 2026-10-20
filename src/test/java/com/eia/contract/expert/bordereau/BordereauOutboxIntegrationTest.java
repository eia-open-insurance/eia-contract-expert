package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractController;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.CreateContractRequest;
import com.eia.contract.expert.ResourceNotFoundException;
import com.eia.contract.expert.technicalaccount.ContractTechnicalAccount;
import com.eia.contract.expert.technicalaccount.CreateTechnicalAccountRequest;
import com.eia.contract.expert.technicalaccount.TechnicalAccountController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.CEDENT;
import static com.eia.contract.expert.Fixtures.FROM;
import static com.eia.contract.expert.Fixtures.REINSURER;
import static com.eia.contract.expert.Fixtures.TENANT;
import static com.eia.contract.expert.Fixtures.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the period-bordereau slice against the H2 schema: creation, newest
 * period first listing, period uniqueness, processing into a technical account
 * (created or reused), failure, invalid transitions, the tenant/contract wall,
 * the outbox events written in the same transaction, and rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class BordereauOutboxIntegrationTest {

    private static final String CONTRACT_NUMBER = "XOL-2026-00091";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate Q1_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q1_END = LocalDate.of(2026, 3, 31);
    private static final LocalDate Q2_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate Q2_END = LocalDate.of(2026, 6, 30);
    private static final LocalDate RECEIVED = LocalDate.of(2026, 4, 15);

    @Autowired
    private ContractController contracts;

    @Autowired
    private BordereauController bordereaux;

    @Autowired
    private TechnicalAccountController accounts;

    @Autowired
    private BordereauHeaderRepository headers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM contract_bordereau");
        jdbc.update("DELETE FROM contract_technical_account");
        jdbc.update("DELETE FROM contract_commutation");
        jdbc.update("DELETE FROM contract_cession");
        jdbc.update("DELETE FROM contract_security");
        jdbc.update("DELETE FROM contract_term");
        jdbc.update("DELETE FROM contract_participant");
        jdbc.update("DELETE FROM contract_layer");
        jdbc.update("DELETE FROM contract");
        jdbc.update("DELETE FROM contract_business_key");
    }

    @Test
    void createsListsAndPublishesTheReceivedEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau q1 = createBordereau(contract, Q1_START, Q1_END);

        assertThat(q1.status()).isEqualTo("RECEIVED");
        assertThat(q1.bordereauNumber()).startsWith("BRD-2026-");
        assertThat(q1.premiumAmount()).isEqualByComparingTo("1000.0000");
        assertThat(q1.lossAmount()).isEqualByComparingTo("400.0000");
        assertThat(q1.commissionAmount()).isEqualByComparingTo("100.0000");
        assertThat(q1.otherChargesAmount()).isEqualByComparingTo("50.0000");
        assertThat(q1.lineCount()).isEqualTo(10);
        assertThat(q1.technicalAccountId()).isNull();

        Map<String, Object> event = eventOfType(contract.id(), "contract.bordereau.received");
        assertThat(event.get("aggregate_type")).isEqualTo("Bordereau");
        assertThat(event.get("aggregate_id")).isEqualTo(q1.id());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("partition_key")).isEqualTo(contract.id().toString());
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"bordereauId\":\"" + q1.id() + "\"")
                .contains("\"bordereauNumber\":\"" + q1.bordereauNumber() + "\"")
                .contains("\"periodStart\":\"2026-01-01\"")
                .contains("\"periodEnd\":\"2026-03-31\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"status\":\"RECEIVED\"")
                .contains("\"premiumAmount\":\"1000.0000\"");

        ContractBordereau q2 = createBordereau(contract, Q2_START, Q2_END);
        List<ContractBordereau> listed = bordereaux.list(TENANT, null, contract.id());
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).id()).isEqualTo(q2.id());
        assertThat(listed.get(0).periodStart()).isEqualTo(Q2_START);
        assertThat(listed.get(1).id()).isEqualTo(q1.id());
        assertThat(listed.get(1).periodStart()).isEqualTo(Q1_START);
    }

    @Test
    void duplicatePeriodIsRejectedAndWritesNoSecondEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        createBordereau(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> createBordereau(contract, Q1_START, Q1_END))
                .isInstanceOf(ContractConflictException.class)
                .hasMessageContaining("already exists")
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("DUPLICATE_BORDEREAU_PERIOD"));

        assertThat(count("contract_bordereau")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.bordereau.received")).isEqualTo(1);
    }

    @Test
    void processingCreatesTechnicalAccountAndLinksIt() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau created = createBordereau(contract, Q1_START, Q1_END);

        ContractBordereau processed = bordereaux.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateBordereauStatusRequest("PROCESSED", "posted to account"));

        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.technicalAccountId()).isNotNull();
        assertThat(processed.notes()).isEqualTo("posted to account");

        List<ContractTechnicalAccount> accountList = accounts.list(TENANT, null, contract.id());
        assertThat(accountList).hasSize(1);
        ContractTechnicalAccount account = accountList.get(0);
        assertThat(account.id()).isEqualTo(processed.technicalAccountId());
        assertThat(account.status()).isEqualTo("DRAFT");
        assertThat(account.accountNumber()).startsWith("TA-2026-");
        assertThat(account.periodStart()).isEqualTo(Q1_START);
        assertThat(account.periodEnd()).isEqualTo(Q1_END);
        assertThat(account.currency()).isEqualTo("USD");
        assertThat(account.cededPremium()).isEqualByComparingTo("1000.0000");
        assertThat(account.cededLosses()).isEqualByComparingTo("400.0000");
        assertThat(account.commission()).isEqualByComparingTo("100.0000");
        assertThat(account.otherCharges()).isEqualByComparingTo("50.0000");

        Map<String, Object> processedEvent =
                eventOfType(contract.id(), "contract.bordereau.processed");
        assertThat(processedEvent.get("aggregate_version")).isEqualTo(2L);
        assertThat((String) processedEvent.get("payload"))
                .contains("\"bordereauId\":\"" + created.id() + "\"")
                .contains("\"technicalAccountId\":\"" + account.id() + "\"")
                .contains("\"status\":\"PROCESSED\"");

        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isEqualTo(1);
        assertThat(bordereauTechnicalAccountId(created.id())).isEqualTo(account.id());
    }

    @Test
    void processingReusesTheExistingTechnicalAccountForThePeriod() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractTechnicalAccount existing = accounts.create(TENANT, null, contract.id(),
                new CreateTechnicalAccountRequest(Q1_START, Q1_END, "USD",
                        new BigDecimal("900.0000"), new BigDecimal("350.0000"),
                        new BigDecimal("90.0000"), new BigDecimal("45.0000")));
        ContractBordereau created = createBordereau(contract, Q1_START, Q1_END);

        ContractBordereau processed = bordereaux.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateBordereauStatusRequest("PROCESSED", null));

        assertThat(processed.technicalAccountId()).isEqualTo(existing.id());
        assertThat(count("contract_technical_account")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.bordereau.processed")).isEqualTo(1);
        assertThat(bordereauTechnicalAccountId(created.id())).isEqualTo(existing.id());
    }

    @Test
    void failingBordereauWritesFailedEventAndCreatesNoAccount() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau created = createBordereau(contract, Q1_START, Q1_END);

        ContractBordereau failed = bordereaux.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateBordereauStatusRequest("FAILED", "missing lines"));

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.notes()).isEqualTo("missing lines");
        assertThat(failed.technicalAccountId()).isNull();
        assertThat(count("contract_technical_account")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isZero();

        Map<String, Object> failedEvent =
                eventOfType(contract.id(), "contract.bordereau.failed");
        assertThat(failedEvent.get("aggregate_version")).isEqualTo(2L);
        assertThat((String) failedEvent.get("payload"))
                .contains("\"status\":\"FAILED\"")
                .contains("\"bordereauId\":\"" + created.id() + "\"");
        assertThat(eventCount(contract.id(), "contract.bordereau.processed")).isZero();
    }

    @Test
    void rejectsInvalidStatusTransitionsAndLeavesTheBordereauUnchanged() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau created = createBordereau(contract, Q1_START, Q1_END);

        bordereaux.updateStatus(TENANT, null, contract.id(), created.id(),
                new UpdateBordereauStatusRequest("PROCESSED", null));

        assertThatThrownBy(() -> bordereaux.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateBordereauStatusRequest("FAILED", "too late")))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
        assertThat(bordereauStatus(created.id())).isEqualTo("PROCESSED");
        assertThat(eventCount(contract.id(), "contract.bordereau.failed")).isZero();

        assertThatThrownBy(() -> bordereaux.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateBordereauStatusRequest("PROCESSED", null)))
                .isInstanceOf(ContractConflictException.class);

        assertThatThrownBy(() -> bordereaux.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateBordereauStatusRequest("RECEIVED", null)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_STATUS"));
    }

    @Test
    void legacyIngestRowsAndPeriodRowsShareTheTableWithoutColliding() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau period = createBordereau(contract, Q1_START, Q1_END);
        insertLegacyHeader(contract, "BRD-2026-00098");
        insertLegacyHeader(contract, "BRD-2026-00099");

        // The period API sees only the period row; the ingest API sees only the
        // null-period rows. Two ingest rows without a period do not collide with
        // the period unique key, and the legacy mapper tolerates a null received_at.
        assertThat(bordereaux.list(TENANT, null, contract.id()))
                .extracting(ContractBordereau::id)
                .containsExactly(period.id());
        assertThat(headers.findAll(TENANT, contract.id()))
                .hasSize(2)
                .allSatisfy(header -> {
                    assertThat(header.bordereauType()).isNull();
                    assertThat(header.receivedAt()).isNull();
                    assertThat(header.periodFrom()).isNull();
                });
    }

    @Test
    void tenantWallHidesTheContractAndItsBordereaux() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau bordereau = createBordereau(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> bordereaux.list(OTHER_TENANT, null, contract.id()))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> bordereaux.create(OTHER_TENANT, null, contract.id(),
                request(Q1_START, Q1_END)))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> bordereaux.updateStatus(OTHER_TENANT, null, contract.id(),
                bordereau.id(), new UpdateBordereauStatusRequest("PROCESSED", null)))
                .isInstanceOf(ContractNotFoundException.class);

        assertThat(bordereauStatus(bordereau.id())).isEqualTo("RECEIVED");
        assertThat(count("contract_bordereau")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.bordereau.received")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.bordereau.processed")).isZero();

        Contract otherContract = createContract("XOL-2026-00092");
        assertThatThrownBy(() -> bordereaux.updateStatus(TENANT, null, otherContract.id(),
                bordereau.id(), new UpdateBordereauStatusRequest("PROCESSED", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(bordereaux.list(TENANT, null, otherContract.id())).isEmpty();
    }

    @Test
    void createRollbackLeavesNoBordereauNoEventAndNoKey() {
        Contract contract = createContract(CONTRACT_NUMBER);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            createBordereau(contract, Q1_START, Q1_END);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract_bordereau")).isZero();
        assertThat(eventCount(contract.id(), "contract.bordereau.received")).isZero();
        assertThat(count("contract_business_key")).isZero();
        assertThat(bordereaux.list(TENANT, null, contract.id())).isEmpty();
    }

    @Test
    void processRollbackLeavesTheBordereauReceivedAndCreatesNoAccountOrEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractBordereau created = createBordereau(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            bordereaux.updateStatus(TENANT, null, contract.id(), created.id(),
                    new UpdateBordereauStatusRequest("PROCESSED", "will roll back"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(bordereauStatus(created.id())).isEqualTo("RECEIVED");
        assertThat(bordereauTechnicalAccountId(created.id())).isNull();
        assertThat(count("contract_technical_account")).isZero();
        assertThat(eventCount(contract.id(), "contract.bordereau.processed")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isZero();
        assertThat(eventCount(contract.id(), "contract.bordereau.received")).isEqualTo(1);
    }

    private Contract createContract(String contractNumber) {
        return contracts.create(new CreateContractRequest(TENANT, contractNumber, "EXCESS_OF_LOSS",
                CEDENT, REINSURER, FROM, TO, "USD", null));
    }

    private ContractBordereau createBordereau(Contract contract, LocalDate start, LocalDate end) {
        return bordereaux.create(TENANT, null, contract.id(), request(start, end));
    }

    private static CreateBordereauRequest request(LocalDate start, LocalDate end) {
        return new CreateBordereauRequest(start, end, RECEIVED, "USD",
                new BigDecimal("1000.0000"), new BigDecimal("400.0000"),
                new BigDecimal("100.0000"), new BigDecimal("50.0000"), 10, null);
    }

    /** A row in the shape the legacy ingest slice writes: no period, no receipt. */
    private void insertLegacyHeader(Contract contract, String bordereauNumber) {
        jdbc.update("""
                INSERT INTO contract_bordereau (
                    id, tenant_id, contract_id, bordereau_number, status, line_count,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'RECEIVED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), TENANT, contract.id(), bordereauNumber);
    }

    private String bordereauStatus(UUID bordereauId) {
        return jdbc.queryForObject(
                "SELECT status FROM contract_bordereau WHERE id = ?",
                String.class, bordereauId);
    }

    private UUID bordereauTechnicalAccountId(UUID bordereauId) {
        return jdbc.queryForObject(
                "SELECT technical_account_id FROM contract_bordereau WHERE id = ?",
                UUID.class, bordereauId);
    }

    private Map<String, Object> eventOfType(UUID contractId, String eventType) {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, status,
                       tenant_id, partition_key, payload
                  FROM outbox_event
                 WHERE partition_key = ? AND event_type = ?
                 ORDER BY event_sequence
                """, contractId.toString(), eventType);
        assertThat(events).as("event " + eventType).hasSize(1);
        return events.get(0);
    }

    private int eventCount(UUID contractId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE partition_key = ? AND event_type = ?
                """, Integer.class, contractId.toString(), eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
