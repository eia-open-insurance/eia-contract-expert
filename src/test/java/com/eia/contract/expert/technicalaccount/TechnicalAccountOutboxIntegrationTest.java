package com.eia.contract.expert.technicalaccount;

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
 * Proves the technical-account slice against the H2 schema: creation, listing,
 * period uniqueness, the status path, the outbox events written in the same
 * transaction, rollback, and the tenant/contract wall.
 */
@SpringBootTest
@ActiveProfiles("test")
class TechnicalAccountOutboxIntegrationTest {

    private static final String CONTRACT_NUMBER = "XOL-2026-00091";
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate Q1_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q1_END = LocalDate.of(2026, 3, 31);
    private static final LocalDate Q2_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate Q2_END = LocalDate.of(2026, 6, 30);

    @Autowired
    private ContractController contracts;

    @Autowired
    private TechnicalAccountController accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
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
    void createsListsAndPublishesTheCreatedEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractTechnicalAccount q1 = createAccount(contract, Q1_START, Q1_END);

        assertThat(q1.status()).isEqualTo("DRAFT");
        assertThat(q1.accountNumber()).startsWith("TA-2026-");
        assertThat(q1.balance()).isEqualByComparingTo("550.0000");
        assertThat(q1.cededPremium()).isEqualByComparingTo("1000.0000");
        assertThat(q1.commission()).isEqualByComparingTo("100.0000");

        Map<String, Object> event = eventOfType(contract.id(),
                "contract.technical-account.created");
        assertThat(event.get("aggregate_type")).isEqualTo("TechnicalAccount");
        assertThat(event.get("aggregate_id")).isEqualTo(q1.id());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("partition_key")).isEqualTo(contract.id().toString());
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"accountId\":\"" + q1.id() + "\"")
                .contains("\"accountNumber\":\"" + q1.accountNumber() + "\"")
                .contains("\"periodStart\":\"2026-01-01\"")
                .contains("\"periodEnd\":\"2026-03-31\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"balance\":\"550.0000\"")
                .contains("\"status\":\"DRAFT\"");

        createAccount(contract, Q2_START, Q2_END);
        List<ContractTechnicalAccount> listed = accounts.list(TENANT, null, contract.id());
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).periodStart()).isEqualTo(Q2_START);
        assertThat(listed.get(1).periodStart()).isEqualTo(Q1_START);
    }

    @Test
    void duplicatePeriodIsRejectedAndWritesNoSecondEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        createAccount(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> createAccount(contract, Q1_START, Q1_END))
                .isInstanceOf(ContractConflictException.class)
                .hasMessageContaining("already exists")
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("DUPLICATE_TECHNICAL_ACCOUNT_PERIOD"));

        assertThat(count("contract_technical_account")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isEqualTo(1);
    }

    @Test
    void issuesThenSettlesAndWritesBothEvents() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractTechnicalAccount created = createAccount(contract, Q1_START, Q1_END);

        ContractTechnicalAccount issued = accounts.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateTechnicalAccountStatusRequest("ISSUED"));
        assertThat(issued.status()).isEqualTo("ISSUED");

        ContractTechnicalAccount settled = accounts.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateTechnicalAccountStatusRequest("SETTLED"));
        assertThat(settled.status()).isEqualTo("SETTLED");

        Map<String, Object> issuedEvent = eventOfType(contract.id(),
                "contract.technical-account.issued");
        assertThat(issuedEvent.get("aggregate_version")).isEqualTo(2L);
        assertThat((String) issuedEvent.get("payload"))
                .contains("\"status\":\"ISSUED\"")
                .contains("\"balance\":\"550.0000\"")
                .contains("\"accountId\":\"" + created.id() + "\"");

        Map<String, Object> settledEvent = eventOfType(contract.id(),
                "contract.technical-account.settled");
        assertThat(settledEvent.get("aggregate_version")).isEqualTo(3L);
        assertThat((String) settledEvent.get("payload"))
                .contains("\"status\":\"SETTLED\"")
                .contains("\"accountNumber\":\"" + created.accountNumber() + "\"");
    }

    @Test
    void rejectsInvalidStatusTransitionsAndLeavesTheAccountUnchanged() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractTechnicalAccount created = createAccount(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> accounts.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateTechnicalAccountStatusRequest("SETTLED")))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
        assertThat(accountStatus(created.id())).isEqualTo("DRAFT");
        assertThat(eventCount(contract.id(), "contract.technical-account.settled")).isZero();

        accounts.updateStatus(TENANT, null, contract.id(), created.id(),
                new UpdateTechnicalAccountStatusRequest("ISSUED"));
        assertThatThrownBy(() -> accounts.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateTechnicalAccountStatusRequest("ISSUED")))
                .isInstanceOf(ContractConflictException.class);

        assertThatThrownBy(() -> accounts.updateStatus(TENANT, null, contract.id(),
                created.id(), new UpdateTechnicalAccountStatusRequest("DRAFT")))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_STATUS"));
    }

    @Test
    void tenantWallHidesTheContractAndItsAccounts() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractTechnicalAccount account = createAccount(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> accounts.list(OTHER_TENANT, null, contract.id()))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> accounts.updateStatus(OTHER_TENANT, null, contract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED")))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> accounts.create(OTHER_TENANT, null, contract.id(),
                request(Q1_START, Q1_END)))
                .isInstanceOf(ContractNotFoundException.class);

        Contract otherContract = createContract("XOL-2026-00092");
        assertThatThrownBy(() -> accounts.updateStatus(TENANT, null, otherContract.id(),
                account.id(), new UpdateTechnicalAccountStatusRequest("ISSUED")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(accountStatus(account.id())).isEqualTo("DRAFT");
        assertThat(count("contract_technical_account")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isEqualTo(1);
    }

    @Test
    void createRollbackLeavesNoAccountAndNoEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            createAccount(contract, Q1_START, Q1_END);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract_technical_account")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isZero();
        assertThat(count("contract_business_key")).isZero();
        assertThat(accounts.list(TENANT, null, contract.id())).isEmpty();
    }

    @Test
    void statusRollbackKeepsDraftAndWritesNoIssuedEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractTechnicalAccount account = createAccount(contract, Q1_START, Q1_END);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            accounts.updateStatus(TENANT, null, contract.id(), account.id(),
                    new UpdateTechnicalAccountStatusRequest("ISSUED"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(accountStatus(account.id())).isEqualTo("DRAFT");
        assertThat(eventCount(contract.id(), "contract.technical-account.issued")).isZero();
        assertThat(eventCount(contract.id(), "contract.technical-account.created")).isEqualTo(1);
    }

    private Contract createContract(String contractNumber) {
        return contracts.create(new CreateContractRequest(TENANT, contractNumber, "EXCESS_OF_LOSS",
                CEDENT, REINSURER, FROM, TO, "USD", null));
    }

    private ContractTechnicalAccount createAccount(Contract contract, LocalDate start, LocalDate end) {
        return accounts.create(TENANT, null, contract.id(), request(start, end));
    }

    private static CreateTechnicalAccountRequest request(LocalDate start, LocalDate end) {
        return new CreateTechnicalAccountRequest(
                start, end, "USD",
                new BigDecimal("1000.0000"), new BigDecimal("400.0000"),
                new BigDecimal("100.0000"), new BigDecimal("50.0000"));
    }

    private String accountStatus(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT status FROM contract_technical_account WHERE id = ?",
                String.class, accountId);
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
