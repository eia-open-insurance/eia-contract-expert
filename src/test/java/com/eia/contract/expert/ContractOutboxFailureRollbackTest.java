package com.eia.contract.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
 * A failure while appending the outbox row must roll the aggregate write back.
 * The outbox repository is replaced so the failure happens after the contract
 * INSERT and inside the controller transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractOutboxFailureRollbackTest {

    @Autowired
    private ContractController controller;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboxRepository outbox;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM contract");
    }

    @Test
    void failedOutboxInsertRollsBackTheContract() {
        when(outbox.insert(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> controller.create(new CreateContractRequest(
                TENANT, "XOL-2026-00091", "EXCESS_OF_LOSS", CEDENT, REINSURER, FROM, TO,
                "USD", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM contract", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
    }
}
