package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ApiError;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractController;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.CreateContractRequest;
import com.eia.contract.expert.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Proves the technical-account line slice against the H2 schema: line CRUD and
 * totals, DRAFT-only mutation, issue-time reconciliation, the line events
 * written in the same transaction, rollback, and the tenant/contract wall.
 */
@SpringBootTest
@ActiveProfiles("test")
class TechnicalAccountLineOutboxIntegrationTest {

    private static final String CONTRACT_NUMBER = "XOL-2026-00101";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate Q1_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q1_END = LocalDate.of(2026, 3, 31);

    @Autowired
    private ContractController contracts;

    @Autowired
    private TechnicalAccountController accounts;

    @Autowired
    private TechnicalAccountLineController lines;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM contract_technical_account_line");
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
    void addsListsAndRemovesLinesWithTheirEvents() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);

        ContractTechnicalAccountLine premium1 =
                addLine(contract, account, "PREMIUM", "600.0000");
        ContractTechnicalAccountLine premium2 =
                addLine(contract, account, "PREMIUM", "400.0000");
        ContractTechnicalAccountLine loss =
                addLine(contract, account, "PAID_LOSS", "400.0000");

        TechnicalAccountLineList view =
                lines.list(TENANT, null, contract.id(), account.id());
        assertThat(view.accountId()).isEqualTo(account.id());
        assertThat(view.lines()).hasSize(3);
        assertThat(view.lines()).extracting(ContractTechnicalAccountLine::id)
                .containsExactlyInAnyOrder(premium1.id(), premium2.id(), loss.id());
        assertThat(view.totals())
                .containsEntry("PREMIUM", "1000.0000")
                .containsEntry("PAID_LOSS", "400.0000")
                .containsEntry("COMMISSION", "0.0000")
                .containsEntry("OTHER", "0.0000");
        assertThat(view.bucketTotals())
                .containsEntry("premium", "1000.0000")
                .containsEntry("loss", "400.0000")
                .containsEntry("commission", "0.0000")
                .containsEntry("other", "0.0000");

        assertThat(eventCount(contract.id(), "contract.technical-account.line-added"))
                .isEqualTo(3);
        Map<String, Object> added = eventOf(contract.id(),
                "contract.technical-account.line-added", premium1.id());
        assertThat(added.get("aggregate_type")).isEqualTo("TechnicalAccountLine");
        assertThat(added.get("aggregate_version")).isEqualTo(1L);
        assertThat(added.get("partition_key")).isEqualTo(contract.id().toString());
        assertThat(added.get("tenant_id")).isEqualTo(TENANT);
        assertThat(added.get("status")).isEqualTo("PENDING");
        assertThat((String) added.get("payload"))
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"accountId\":\"" + account.id() + "\"")
                .contains("\"lineId\":\"" + premium1.id() + "\"")
                .contains("\"lineType\":\"PREMIUM\"")
                .contains("\"amount\":\"600.0000\"");
        lines.remove(TENANT, null, contract.id(), account.id(), premium2.id());

        TechnicalAccountLineList after =
                lines.list(TENANT, null, contract.id(), account.id());
        assertThat(after.lines()).hasSize(2);
        assertThat(after.totals()).containsEntry("PREMIUM", "600.0000");
        Map<String, Object> removed = eventOf(contract.id(),
                "contract.technical-account.line-removed", premium2.id());
        assertThat(removed.get("aggregate_type")).isEqualTo("TechnicalAccountLine");
        assertThat(removed.get("partition_key")).isEqualTo(contract.id().toString());
        assertThat((String) removed.get("payload"))
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"accountId\":\"" + account.id() + "\"")
                .contains("\"lineId\":\"" + premium2.id() + "\"")
                .contains("\"lineType\":\"PREMIUM\"")
                .contains("\"amount\":\"400.0000\"");
    }

    @Test
    void reconcilesLinesOnIssueAndThenFreezesThem() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);
        ContractTechnicalAccountLine premium =
                addLine(contract, account, "PREMIUM", "1000.0000");
        addLine(contract, account, "PAID_LOSS", "250.0000");
        addLine(contract, account, "OUTSTANDING_LOSS", "100.0000");
        addLine(contract, account, "IBNR", "50.0000");
        addLine(contract, account, "COMMISSION", "80.0000");
        addLine(contract, account, "BROKERAGE", "20.0000");
        addLine(contract, account, "OTHER", "30.0000");
        addLine(contract, account, "TAX", "20.0000");

        ContractTechnicalAccount issued = accounts.updateStatus(TENANT, null, contract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED"));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(eventCount(contract.id(), "contract.technical-account.issued")).isEqualTo(1);

        assertThatThrownBy(() -> addLine(contract, account, "PREMIUM", "10.0000"))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("ACCOUNT_NOT_EDITABLE"));
        assertThatThrownBy(() -> lines.remove(TENANT, null, contract.id(), account.id(),
                premium.id()))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("ACCOUNT_NOT_EDITABLE"));

        assertThat(count("contract_technical_account_line")).isEqualTo(8);
        assertThat(eventCount(contract.id(), "contract.technical-account.line-removed"))
                .isZero();
    }

    @Test
    void refusesIssueWhenLineTotalsDoNotReconcile() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);
        addLine(contract, account, "PREMIUM", "900.0000");
        addLine(contract, account, "PAID_LOSS", "400.0000");
        addLine(contract, account, "COMMISSION", "100.0000");
        addLine(contract, account, "OTHER", "50.0000");

        assertThatThrownBy(() -> accounts.updateStatus(TENANT, null, contract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED")))
                .isInstanceOf(TechnicalAccountLinesDoNotReconcileException.class)
                .satisfies(ex -> {
                    TechnicalAccountLinesDoNotReconcileException mismatch =
                            (TechnicalAccountLinesDoNotReconcileException) ex;
                    assertThat(mismatch.code()).isEqualTo("ACCOUNT_LINES_DO_NOT_RECONCILE");
                    assertThat(mismatch.mismatches()).hasSize(1);
                    assertThat(mismatch.mismatches().get(0).field()).isEqualTo("PREMIUM");
                    assertThat(mismatch.mismatches().get(0).message())
                            .contains("900.0000")
                            .contains("1000.0000");
                });

        assertThat(accountStatus(account.id())).isEqualTo("DRAFT");
        assertThat(eventCount(contract.id(), "contract.technical-account.issued")).isZero();

        // The detail can be corrected while the account is still DRAFT, after
        // which the same issue transition succeeds.
        addLine(contract, account, "PREMIUM", "100.0000");
        ContractTechnicalAccount issued = accounts.updateStatus(TENANT, null, contract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED"));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(eventCount(contract.id(), "contract.technical-account.issued")).isEqualTo(1);
    }

    @Test
    void refusesIssueWhenHeaderComponentsHaveNoLines() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);
        addLine(contract, account, "PREMIUM", "1000.0000");

        assertThatThrownBy(() -> accounts.updateStatus(TENANT, null, contract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED")))
                .isInstanceOf(TechnicalAccountLinesDoNotReconcileException.class)
                .satisfies(ex -> assertThat(
                        ((TechnicalAccountLinesDoNotReconcileException) ex).mismatches())
                        .extracting(ApiError.FieldViolation::field)
                        .containsExactly("LOSS", "COMMISSION", "OTHER"));

        assertThat(accountStatus(account.id())).isEqualTo("DRAFT");
    }

    @Test
    void persistsEveryExpandedLineTypeAndRollsUpItsBucket() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);

        for (TechnicalAccountLineType type : TechnicalAccountLineType.values()) {
            ContractTechnicalAccountLine line =
                    addLine(contract, account, type.name(), "1.0000");
            assertThat(line.lineType()).isEqualTo(type);
        }

        TechnicalAccountLineList view =
                lines.list(TENANT, null, contract.id(), account.id());
        assertThat(view.lines()).hasSize(TechnicalAccountLineType.values().length);
        assertThat(view.lines())
                .extracting(ContractTechnicalAccountLine::lineType)
                .containsExactlyInAnyOrder(TechnicalAccountLineType.values());
        assertThat(view.totals())
                .containsEntry("PREMIUM", "1.0000")
                .containsEntry("COMMISSION", "1.0000")
                .containsEntry("BROKERAGE", "1.0000")
                .containsEntry("PAID_LOSS", "1.0000")
                .containsEntry("OUTSTANDING_LOSS", "1.0000")
                .containsEntry("IBNR", "1.0000")
                .containsEntry("ALAE", "1.0000")
                .containsEntry("REINSTATEMENT", "1.0000")
                .containsEntry("CASH_CALL", "1.0000")
                .containsEntry("FUNDS_WITHHELD", "1.0000")
                .containsEntry("TAX", "1.0000")
                .containsEntry("ADJUSTMENT", "1.0000")
                .containsEntry("OTHER", "1.0000")
                .hasSize(13);
        assertThat(view.bucketTotals())
                .containsEntry("premium", "1.0000")
                .containsEntry("loss", "6.0000")
                .containsEntry("commission", "2.0000")
                .containsEntry("other", "4.0000")
                .hasSize(4);
    }

    @Test
    void databaseCheckRejectsAnUnknownLineType() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO contract_technical_account_line
                    (id, tenant_id, account_id, line_type, amount, created_at)
                VALUES (?, ?, ?, 'FEE', 1.0000, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), TENANT, account.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("contract_technical_account_line")).isZero();
    }

    @Test
    void issuesAHeaderOnlyLegacyAccountWithoutLines() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);

        ContractTechnicalAccount issued = accounts.updateStatus(TENANT, null, contract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED"));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(count("contract_technical_account_line")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.line-added")).isZero();
    }

    @Test
    void validatesTheAmountAndLineType() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);

        assertThatThrownBy(() -> addLine(contract, account, "PREMIUM", "0"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_AMOUNT"));
        assertThatThrownBy(() -> addLine(contract, account, "LOSS", "10.0000"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_LINE_TYPE"));
        assertThatThrownBy(() -> addLine(contract, account, "FEE", "10.0000"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_LINE_TYPE"));

        assertThat(count("contract_technical_account_line")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.line-added")).isZero();
    }

    @Test
    void tenantAndContractWallHideLines() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);
        ContractTechnicalAccountLine line = addLine(contract, account, "PREMIUM", "1000.0000");

        assertThatThrownBy(() -> lines.list(OTHER_TENANT, null, contract.id(), account.id()))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> lines.add(OTHER_TENANT, null, contract.id(), account.id(),
                request("PREMIUM", "10.0000")))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> lines.remove(OTHER_TENANT, null, contract.id(), account.id(),
                line.id()))
                .isInstanceOf(ContractNotFoundException.class);

        Contract otherContract = createContract("XOL-2026-00102");
        assertThatThrownBy(() -> lines.list(TENANT, null, otherContract.id(), account.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> lines.add(TENANT, null, otherContract.id(), account.id(),
                request("PREMIUM", "10.0000")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("contract_technical_account_line")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.technical-account.line-added"))
                .isEqualTo(1);
    }

    @Test
    void addRollbackLeavesNoLineAndNoEvent() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            addLine(contract, account, "PREMIUM", "1000.0000");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract_technical_account_line")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.line-added")).isZero();
        assertThat(lines.list(TENANT, null, contract.id(), account.id()).lines()).isEmpty();
    }

    @Test
    void removeRollbackKeepsTheLineAndWritesNoEvent() {
        Contract contract = createContract();
        ContractTechnicalAccount account = createAccount(contract);
        ContractTechnicalAccountLine line = addLine(contract, account, "PREMIUM", "1000.0000");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            lines.remove(TENANT, null, contract.id(), account.id(), line.id());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract_technical_account_line")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.technical-account.line-removed")).isZero();
        assertThat(lines.list(TENANT, null, contract.id(), account.id()).lines()).hasSize(1);
    }

    private Contract createContract() {
        return createContract(CONTRACT_NUMBER);
    }

    private Contract createContract(String contractNumber) {
        return contracts.create(new CreateContractRequest(TENANT, contractNumber, "EXCESS_OF_LOSS",
                CEDENT, REINSURER, FROM, TO, "USD", null));
    }

    private ContractTechnicalAccount createAccount(Contract contract) {
        return accounts.create(TENANT, null, contract.id(), new CreateTechnicalAccountRequest(
                Q1_START, Q1_END, "USD",
                new BigDecimal("1000.0000"), new BigDecimal("400.0000"),
                new BigDecimal("100.0000"), new BigDecimal("50.0000")));
    }

    private ContractTechnicalAccountLine addLine(Contract contract,
                                                 ContractTechnicalAccount account,
                                                 String lineType, String amount) {
        return lines.add(TENANT, null, contract.id(), account.id(),
                request(lineType, amount));
    }

    private static CreateTechnicalAccountLineRequest request(String lineType, String amount) {
        return new CreateTechnicalAccountLineRequest(
                lineType, "REF-" + lineType, "Test " + lineType + " line",
                new BigDecimal(amount));
    }

    private String accountStatus(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT status FROM contract_technical_account WHERE id = ?",
                String.class, accountId);
    }

    private Map<String, Object> eventOf(UUID contractId, String eventType, UUID aggregateId) {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, status,
                       tenant_id, partition_key, payload
                  FROM outbox_event
                 WHERE partition_key = ? AND event_type = ? AND aggregate_id = ?
                 ORDER BY event_sequence
                """, contractId.toString(), eventType, aggregateId);
        assertThat(events).as("event " + eventType + " for " + aggregateId).hasSize(1);
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
