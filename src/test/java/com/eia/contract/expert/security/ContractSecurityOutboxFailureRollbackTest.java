package com.eia.contract.expert.security;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractStatus;
import com.eia.contract.expert.ContractType;
import com.eia.contract.expert.CreateContractRequest;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.CEDENT;
import static com.eia.contract.expert.Fixtures.FROM;
import static com.eia.contract.expert.Fixtures.REINSURER;
import static com.eia.contract.expert.Fixtures.TENANT;
import static com.eia.contract.expert.Fixtures.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A failure while appending the outbox row must roll the security INSERT back.
 * The outbox repository is replaced so the failure happens after the row INSERT
 * and inside the controller transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractSecurityOutboxFailureRollbackTest {

    @Autowired
    private ContractSecurityController controller;

    @Autowired
    private ContractRepository contracts;

    @Autowired
    private SecurityRepository securities;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboxRepository outbox;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM contract_security");
        jdbc.update("DELETE FROM contract");
    }

    @Test
    void failedOutboxInsertRollsBackTheSecurityRow() {
        Contract contract = contracts.create(new CreateContractRequest(
                TENANT, "XOL-2026-00095", "EXCESS_OF_LOSS", CEDENT, REINSURER, FROM, TO,
                "USD", null), ContractType.EXCESS_OF_LOSS, ContractStatus.DRAFT);
        when(outbox.insert(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> controller.create(TENANT, null, contract.id(),
                new CreateContractSecurityRequest("LETTER_OF_CREDIT",
                        new BigDecimal("5000000"), new BigDecimal("4000000"), "USD",
                        null, LocalDate.now().plusDays(30), "APR-ROLLBACK", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(count("contract_security")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    /**
     * The status UPDATE happens before the outbox append; the failed append must
     * roll the stored status and timestamp back to ACTIVE.
     */
    @Test
    void failedOutboxInsertRollsBackTheStatusChange() {
        Contract contract = contracts.create(new CreateContractRequest(
                TENANT, "XOL-2026-00096", "EXCESS_OF_LOSS", CEDENT, REINSURER, FROM, TO,
                "USD", null), ContractType.EXCESS_OF_LOSS, ContractStatus.DRAFT);
        ContractSecurity active = securities.create(ContractSecurity.create(
                TENANT, contract.id(), "LETTER_OF_CREDIT", new BigDecimal("5000000"),
                new BigDecimal("4000000"), "USD", null, null, FROM, null,
                LocalDate.now().plusDays(30), "ACTIVE", null, null, null, null, null));
        when(outbox.insert(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> controller.updateStatus(TENANT, null, contract.id(),
                active.id(), new UpdateContractSecurityStatusRequest("RELEASED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(status(active.id())).isEqualTo("ACTIVE");
        assertThat(count("outbox_event")).isZero();
    }

    private String status(UUID securityId) {
        return jdbc.queryForObject("SELECT status FROM contract_security WHERE id = ?",
                String.class, securityId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
