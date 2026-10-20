package com.eia.contract.expert.security;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractController;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.CreateContractRequest;
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
 * Proves the public collateral slice against the H2 schema: creation with the
 * derived ACTIVE/EXPIRED status, newest-first listing with exactly the public
 * fields, validation, the tenant/contract wall, the outbox event written in the
 * same transaction, and rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractSecurityOutboxIntegrationTest {

    private static final String CONTRACT_NUMBER = "XOL-2026-00093";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");

    @Autowired
    private ContractController contracts;

    @Autowired
    private ContractSecurityController security;

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
    void createsListsAndPublishesTheRecordedEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);
        LocalDate expiry = LocalDate.now().plusDays(30);

        ContractSecurityView created = create(contract, request("LETTER_OF_CREDIT",
                "5000000", "4000000", "USD", REINSURER, expiry, "  APR-2026-0007  "));

        assertThat(created.securityType()).isEqualTo("LETTER_OF_CREDIT");
        assertThat(created.requiredAmount()).isEqualByComparingTo("5000000.0000");
        assertThat(created.heldAmount()).isEqualByComparingTo("4000000.0000");
        assertThat(created.currency()).isEqualTo("USD");
        assertThat(created.providerPartyId()).isEqualTo(REINSURER);
        assertThat(created.expiryDate()).isEqualTo(expiry);
        assertThat(created.approvalRef()).isEqualTo("APR-2026-0007");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.createdAt()).isNotNull();

        Map<String, Object> event = eventOfType(contract.id(), "contract.security.recorded");
        assertThat(event.get("aggregate_type")).isEqualTo("Security");
        assertThat(event.get("aggregate_id")).isEqualTo(created.id());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("partition_key")).isEqualTo(contract.id().toString());
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"securityId\":\"" + created.id() + "\"")
                .contains("\"securityType\":\"LETTER_OF_CREDIT\"")
                .contains("\"requiredAmount\":\"5000000.0000\"")
                .contains("\"heldAmount\":\"4000000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"status\":\"ACTIVE\"");

        List<ContractSecurityView> listed = security.list(TENANT, null, contract.id());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).isEqualTo(created);
    }

    @Test
    void pastExpiryIsRecordedExpiredAndTheListIsNewestFirst() throws Exception {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractSecurityView active = create(contract, request("LETTER_OF_CREDIT",
                "5000000", "4000000", "USD", REINSURER,
                LocalDate.now().plusDays(30), null));
        Thread.sleep(10);
        ContractSecurityView expired = create(contract, request("BANK_GUARANTEE",
                "1000000", "0", "USD", null, LocalDate.now().minusDays(1), "APR-OLD"));

        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(expired.status()).isEqualTo("EXPIRED");

        List<ContractSecurityView> listed = security.list(TENANT, null, contract.id());
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).id()).isEqualTo(expired.id());
        assertThat(listed.get(0).status()).isEqualTo("EXPIRED");
        assertThat(listed.get(1).id()).isEqualTo(active.id());
        assertThat(listed.get(1).status()).isEqualTo("ACTIVE");

        assertThat(eventCount(contract.id(), "contract.security.recorded")).isEqualTo(2);
        assertThat(expiredEvent(contract.id()))
                .contains("\"securityId\":\"" + expired.id() + "\"")
                .contains("\"status\":\"EXPIRED\"");
    }

    @Test
    void validationRejectsBadInputAndWritesNothing() {
        Contract contract = createContract(CONTRACT_NUMBER);

        assertThatThrownBy(() -> create(contract, request(null, "5000000", "0",
                "USD", null, null, null)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_SECURITY_TYPE"));
        assertThatThrownBy(() -> create(contract, request("LETTER_OF_CREDIT", "0", "0",
                "USD", null, null, null)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_REQUIRED_AMOUNT"));
        assertThatThrownBy(() -> create(contract, request("LETTER_OF_CREDIT", "5000000",
                "-1", "USD", null, null, null)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_HELD_AMOUNT"));
        assertThatThrownBy(() -> create(contract, request("LETTER_OF_CREDIT", "5000000",
                "0", null, null, null, null)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                        .isEqualTo("INVALID_CURRENCY"));
        assertThatThrownBy(() -> create(contract, request("LETTER_OF_CREDIT", "5000000",
                "0", "GBP", null, null, null)))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("SECURITY_CURRENCY_MISMATCH"));

        assertThat(count("contract_security")).isZero();
        assertThat(eventCount(contract.id(), "contract.security.recorded")).isZero();
    }

    @Test
    void tenantAndContractWallHideTheSecurityRows() {
        Contract contract = createContract(CONTRACT_NUMBER);
        ContractSecurityView created = create(contract, request("LETTER_OF_CREDIT",
                "5000000", "4000000", "USD", null, null, null));

        assertThatThrownBy(() -> security.list(OTHER_TENANT, null, contract.id()))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> security.create(OTHER_TENANT, null, contract.id(),
                request("LETTER_OF_CREDIT", "1", "0", "USD", null, null, null)))
                .isInstanceOf(ContractNotFoundException.class);
        assertThatThrownBy(() -> security.list(TENANT, null, UUID.randomUUID()))
                .isInstanceOf(ContractNotFoundException.class);

        Contract otherContract = createContract("XOL-2026-00094");
        assertThat(security.list(TENANT, null, otherContract.id())).isEmpty();

        assertThat(count("contract_security")).isEqualTo(1);
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(eventCount(contract.id(), "contract.security.recorded")).isEqualTo(1);
    }

    @Test
    void createRollbackLeavesNoSecurityAndNoEvent() {
        Contract contract = createContract(CONTRACT_NUMBER);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            create(contract, request("LETTER_OF_CREDIT", "5000000", "4000000", "USD",
                    REINSURER, LocalDate.now().plusDays(30), "APR-ROLLBACK"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract_security")).isZero();
        assertThat(eventCount(contract.id(), "contract.security.recorded")).isZero();
        assertThat(security.list(TENANT, null, contract.id())).isEmpty();
    }

    private Contract createContract(String contractNumber) {
        return contracts.create(new CreateContractRequest(TENANT, contractNumber,
                "EXCESS_OF_LOSS", CEDENT, REINSURER, FROM, TO, "USD", null));
    }

    private ContractSecurityView create(Contract contract, CreateContractSecurityRequest request) {
        return security.create(TENANT, null, contract.id(), request);
    }

    private static CreateContractSecurityRequest request(
            String securityType, String required, String held, String currency,
            UUID provider, LocalDate expiry, String approvalRef) {
        return new CreateContractSecurityRequest(securityType,
                required == null ? null : new BigDecimal(required),
                held == null ? null : new BigDecimal(held),
                currency, provider, expiry, approvalRef, null);
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

    private String expiredEvent(UUID contractId) {
        List<String> payloads = jdbc.queryForList("""
                SELECT payload FROM outbox_event
                 WHERE partition_key = ? AND event_type = ?
                 ORDER BY event_sequence DESC
                """, String.class, contractId.toString(), "contract.security.recorded");
        return payloads.get(0);
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
