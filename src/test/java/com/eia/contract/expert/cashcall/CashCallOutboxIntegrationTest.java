package com.eia.contract.expert.cashcall;

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
 * Proves the cash-call slice against the H2 schema: creation, newest-first
 * listing, unique call numbers per contract, the full status path
 * (REQUESTED -> ISSUED -> SETTLED and REQUESTED -> CANCELLED), invalid and
 * terminal transitions, the tenant/contract wall, the outbox events written in
 * the same transaction, and rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class CashCallOutboxIntegrationTest {

    private static final String CONTRACT_NUMBER = "XOL-2026-00091";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate Q1_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q1_END = LocalDate.of(2026, 3, 31);
    private static final LocalDate Q2_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate Q2_END = LocalDate.of(2026, 6, 30);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 4, 15);
    private static final String SETTLEMENT_REFERENCE = "BANK-REF-2026-0001";

    @Autowired
    private ContractController contracts;

    @Autowired
    private CashCallController cashCalls;

    @Autowired
    private CashCallRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM contract_cash_call");
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
    void createsListsAndPublishesTheRequestedEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall q1 = createCashCall(contract, Q1_START, Q1_END);

        assertThat(q1.status()).isEqualTo("REQUESTED");
        assertThat(q1.callNumber()).startsWith("CC-2026-");
        assertThat(q1.amount()).isEqualByComparingTo("250000.0000");
        assertThat(q1.currency()).isEqualTo("USD");
        assertThat(q1.dueDate()).isEqualTo(DUE_DATE);
        assertThat(q1.notes()).isEqualTo("Q1 cash call");
        assertThat(q1.settlementReference()).isNull();

        Map<String, Object> event = eventOfType(contract.id(),
                "contract.cash-call.requested");
        assertThat(event.get("aggregate_type")).isEqualTo("CashCall");
        assertThat(event.get("aggregate_id")).isEqualTo(q1.id());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("partition_key")).isEqualTo(contract.id().toString());
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"cashCallId\":\"" + q1.id() + "\"")
                .contains("\"callNumber\":\"" + q1.callNumber() + "\"")
                .contains("\"period\":{\"end\":\"2026-03-31\",\"start\":\"2026-01-01\"}")
                .contains("\"periodStart\":\"2026-01-01\"")
                .contains("\"periodEnd\":\"2026-03-31\"")
                .contains("\"amount\":\"250000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"dueDate\":\"2026-04-15\"")
                .contains("\"status\":\"REQUESTED\"")
                .doesNotContain("settlementReference");

        ContractCashCall q2 = createCashCall(contract, Q2_START, Q2_END);
        assertThat(q2.callNumber()).isNotEqualTo(q1.callNumber());

        List<ContractCashCall> listed = cashCalls.list(TENANT, null, contract.id());
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).id()).isEqualTo(q2.id());
        assertThat(listed.get(0).periodStart()).isEqualTo(Q2_START);
        assertThat(listed.get(1).id()).isEqualTo(q1.id());
        assertThat(listed.get(1).periodStart()).isEqualTo(Q1_START);
    }

    @Test
    void issuesSettlesAndCancelsWithTheirEvents() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall issued = createCashCall(contract, Q1_START, Q1_END);
        ContractCashCall cancelled = createCashCall(contract, Q2_START, Q2_END);

        ContractCashCall afterIssue = updateStatus(contract, issued, "ISSUED");
        assertThat(afterIssue.status()).isEqualTo("ISSUED");
        assertThat(afterIssue.settlementReference()).isNull();
        ContractCashCall afterSettle = updateStatus(contract, issued, "SETTLED",
                "  " + SETTLEMENT_REFERENCE + "  ");
        assertThat(afterSettle.status()).isEqualTo("SETTLED");
        assertThat(afterSettle.settlementReference()).isEqualTo(SETTLEMENT_REFERENCE);
        ContractCashCall afterCancel = updateStatus(contract, cancelled, "CANCELLED");
        assertThat(afterCancel.status()).isEqualTo("CANCELLED");
        assertThat(afterCancel.settlementReference()).isNull();

        Map<String, Object> issuedEvent = eventOfType(contract.id(),
                "contract.cash-call.issued");
        assertThat(issuedEvent.get("aggregate_version")).isEqualTo(2L);
        assertThat((String) issuedEvent.get("payload"))
                .contains("\"cashCallId\":\"" + issued.id() + "\"")
                .contains("\"status\":\"ISSUED\"")
                .doesNotContain("settlementReference");

        Map<String, Object> settledEvent = eventOfType(contract.id(),
                "contract.cash-call.settled");
        assertThat(settledEvent.get("aggregate_version")).isEqualTo(3L);
        assertThat((String) settledEvent.get("payload"))
                .contains("\"callNumber\":\"" + issued.callNumber() + "\"")
                .contains("\"status\":\"SETTLED\"")
                .contains("\"settlementReference\":\"" + SETTLEMENT_REFERENCE + "\"");

        Map<String, Object> cancelledEvent = eventOfType(contract.id(),
                "contract.cash-call.cancelled");
        assertThat(cancelledEvent.get("aggregate_version")).isEqualTo(2L);
        assertThat((String) cancelledEvent.get("payload"))
                .contains("\"cashCallId\":\"" + cancelled.id() + "\"")
                .contains("\"status\":\"CANCELLED\"")
                .doesNotContain("settlementReference");

        assertThat(cashCallStatus(issued.id())).isEqualTo("SETTLED");
        assertThat(settlementReference(issued.id())).isEqualTo(SETTLEMENT_REFERENCE);
        assertThat(cashCallStatus(cancelled.id())).isEqualTo("CANCELLED");
        assertThat(settlementReference(cancelled.id())).isNull();
        assertThat(eventCount(contract.id(), "contract.cash-call.issued")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.cash-call.settled")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.cash-call.cancelled")).isEqualTo(1);
    }

    @Test
    void settlementReferenceIsRequiredTrimmedAndLengthChecked() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall cashCall = createCashCall(contract, Q1_START, Q1_END);
        updateStatus(contract, cashCall, "ISSUED");

        assertThatThrownBy(() -> updateStatus(contract, cashCall, "SETTLED"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_REFERENCE"));
        assertThatThrownBy(() -> updateStatus(contract, cashCall, "SETTLED", "   "))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_REFERENCE"));
        String tooLong = "R".repeat(CashCallRules.SETTLEMENT_REFERENCE_MAX_LENGTH + 1);
        assertThatThrownBy(() -> updateStatus(contract, cashCall, "SETTLED", tooLong))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_REFERENCE"));

        assertThat(cashCallStatus(cashCall.id())).isEqualTo("ISSUED");
        assertThat(settlementReference(cashCall.id())).isNull();
        assertThat(eventCount(contract.id(), "contract.cash-call.settled")).isZero();

        String maxReference = "R".repeat(CashCallRules.SETTLEMENT_REFERENCE_MAX_LENGTH);
        ContractCashCall settled = updateStatus(contract, cashCall, "SETTLED",
                "  " + maxReference + "  ");
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.settlementReference()).isEqualTo(maxReference);
        assertThat(cashCallStatus(cashCall.id())).isEqualTo("SETTLED");
        assertThat(settlementReference(cashCall.id())).isEqualTo(maxReference);

        Map<String, Object> settledEvent = eventOfType(contract.id(),
                "contract.cash-call.settled");
        assertThat((String) settledEvent.get("payload"))
                .contains("\"settlementReference\":\"" + maxReference + "\"");

        List<ContractCashCall> listed = cashCalls.list(TENANT, null, contract.id());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).settlementReference()).isEqualTo(maxReference);
    }

    @Test
    void issueAndCancelIgnoreTheReference() {
        Contract contract = createContract(CONTRACT_NUMBER);
        String ignored = "R".repeat(500);

        ContractCashCall toIssue = createCashCall(contract, Q1_START, Q1_END);
        ContractCashCall afterIssue = updateStatus(contract, toIssue, "ISSUED", ignored);
        assertThat(afterIssue.status()).isEqualTo("ISSUED");
        assertThat(afterIssue.settlementReference()).isNull();
        assertThat(settlementReference(toIssue.id())).isNull();
        assertThat((String) eventOfType(contract.id(), "contract.cash-call.issued")
                .get("payload")).doesNotContain("settlementReference");

        ContractCashCall toCancel = createCashCall(contract, Q2_START, Q2_END);
        ContractCashCall afterCancel = updateStatus(contract, toCancel, "CANCELLED", ignored);
        assertThat(afterCancel.status()).isEqualTo("CANCELLED");
        assertThat(afterCancel.settlementReference()).isNull();
        assertThat(settlementReference(toCancel.id())).isNull();
        assertThat((String) eventOfType(contract.id(), "contract.cash-call.cancelled")
                .get("payload")).doesNotContain("settlementReference");
    }

    @Test
    void rejectsInvalidStatusTransitionsAndLeavesRowsUnchanged() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall issued = createCashCall(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> updateStatus(contract, issued, "SETTLED"))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
        assertThat(cashCallStatus(issued.id())).isEqualTo("REQUESTED");
        assertThat(eventCount(contract.id(), "contract.cash-call.settled")).isZero();

        updateStatus(contract, issued, "ISSUED");
        assertThatThrownBy(() -> updateStatus(contract, issued, "CANCELLED"))
                .isInstanceOf(ContractConflictException.class);
        assertThatThrownBy(() -> updateStatus(contract, issued, "ISSUED"))
                .isInstanceOf(ContractConflictException.class);
        assertThat(cashCallStatus(issued.id())).isEqualTo("ISSUED");

        updateStatus(contract, issued, "SETTLED", SETTLEMENT_REFERENCE);
        assertThatThrownBy(() -> updateStatus(contract, issued, "ISSUED"))
                .isInstanceOf(ContractConflictException.class);
        assertThatThrownBy(() -> updateStatus(contract, issued, "CANCELLED"))
                .isInstanceOf(ContractConflictException.class);
        assertThat(cashCallStatus(issued.id())).isEqualTo("SETTLED");

        ContractCashCall cancelled = createCashCall(contract, Q2_START, Q2_END);
        updateStatus(contract, cancelled, "CANCELLED");
        assertThatThrownBy(() -> updateStatus(contract, cancelled, "ISSUED"))
                .isInstanceOf(ContractConflictException.class);
        assertThatThrownBy(() -> updateStatus(contract, cancelled, "SETTLED"))
                .isInstanceOf(ContractConflictException.class);
        assertThat(cashCallStatus(cancelled.id())).isEqualTo("CANCELLED");

        assertThatThrownBy(() -> updateStatus(contract, issued, "REQUESTED"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_STATUS"));
        assertThatThrownBy(() -> updateStatus(contract, issued, "APPROVED"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_STATUS"));

        assertThat(eventCount(contract.id(), "contract.cash-call.requested")).isEqualTo(2);
        assertThat(eventCount(contract.id(), "contract.cash-call.issued")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.cash-call.settled")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.cash-call.cancelled")).isEqualTo(1);
    }

    @Test
    void callNumberIsUniquePerContractButNotAcrossContracts() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall first = createCashCall(contract, Q1_START, Q1_END);

        ContractCashCall duplicate = ContractCashCall.requested(
                TENANT, contract.id(), first.callNumber(), Q2_START, Q2_END,
                new BigDecimal("10.0000"), "USD", DUE_DATE, null);
        assertThatThrownBy(() -> repository.create(duplicate))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("DUPLICATE_CASH_CALL_NUMBER"));
        assertThat(count("contract_cash_call")).isEqualTo(1);

        Contract otherContract = createContract("XOL-2026-00092");
        ContractCashCall sameNumberForAnotherContract = ContractCashCall.requested(
                TENANT, otherContract.id(), first.callNumber(), Q1_START, Q1_END,
                new BigDecimal("10.0000"), "USD", DUE_DATE, null);
        ContractCashCall created = repository.create(sameNumberForAnotherContract);
        assertThat(created.callNumber()).isEqualTo(first.callNumber());
        assertThat(count("contract_cash_call")).isEqualTo(2);
    }

    @Test
    void tenantAndContractWallHideTheCashCalls() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall cashCall = createCashCall(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> cashCalls.list(OTHER_TENANT, null, contract.id()))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> cashCalls.create(OTHER_TENANT, null, contract.id(),
                request(Q1_START, Q1_END)))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> cashCalls.updateStatus(OTHER_TENANT, null, contract.id(),
                cashCall.id(), new UpdateCashCallStatusRequest("ISSUED")))
                .isInstanceOf(ContractNotFoundException.class);

        Contract otherContract = createContract("XOL-2026-00092");
        assertThatThrownBy(() -> cashCalls.updateStatus(TENANT, null, otherContract.id(),
                cashCall.id(), new UpdateCashCallStatusRequest("ISSUED")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(cashCalls.list(TENANT, null, otherContract.id())).isEmpty();

        assertThat(cashCallStatus(cashCall.id())).isEqualTo("REQUESTED");
        assertThat(count("contract_cash_call")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.cash-call.requested")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.cash-call.issued")).isZero();
    }

    @Test
    void createRollbackLeavesNoCashCallNoEventAndNoKey() {
        Contract contract = createContract(CONTRACT_NUMBER);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            createCashCall(contract, Q1_START, Q1_END);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract_cash_call")).isZero();
        assertThat(eventCount(contract.id(), "contract.cash-call.requested")).isZero();
        assertThat(count("contract_business_key")).isZero();
        assertThat(cashCalls.list(TENANT, null, contract.id())).isEmpty();
    }

    @Test
    void statusRollbackKeepsRequestedAndWritesNoIssuedEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractCashCall cashCall = createCashCall(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            updateStatus(contract, cashCall, "ISSUED");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cashCallStatus(cashCall.id())).isEqualTo("REQUESTED");
        assertThat(eventCount(contract.id(), "contract.cash-call.issued")).isZero();
        assertThat(eventCount(contract.id(), "contract.cash-call.requested")).isEqualTo(1);
    }

    private Contract createContract(String contractNumber) {
        return contracts.create(new CreateContractRequest(TENANT, contractNumber, "EXCESS_OF_LOSS",
                CEDENT, REINSURER, FROM, TO, "USD", null));
    }

    private ContractCashCall createCashCall(Contract contract, LocalDate start, LocalDate end) {
        return cashCalls.create(TENANT, null, contract.id(), request(start, end));
    }

    private ContractCashCall updateStatus(Contract contract, ContractCashCall cashCall,
                                          String status) {
        return updateStatus(contract, cashCall, status, null);
    }

    private ContractCashCall updateStatus(Contract contract, ContractCashCall cashCall,
                                          String status, String reference) {
        return cashCalls.updateStatus(TENANT, null, contract.id(), cashCall.id(),
                new UpdateCashCallStatusRequest(status, reference));
    }

    private static CreateCashCallRequest request(LocalDate start, LocalDate end) {
        return new CreateCashCallRequest(start, end, new BigDecimal("250000.0000"), "USD",
                DUE_DATE, "Q1 cash call");
    }

    private String cashCallStatus(UUID cashCallId) {
        return jdbc.queryForObject(
                "SELECT status FROM contract_cash_call WHERE id = ?",
                String.class, cashCallId);
    }

    private String settlementReference(UUID cashCallId) {
        return jdbc.queryForObject(
                "SELECT settlement_reference FROM contract_cash_call WHERE id = ?",
                String.class, cashCallId);
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
