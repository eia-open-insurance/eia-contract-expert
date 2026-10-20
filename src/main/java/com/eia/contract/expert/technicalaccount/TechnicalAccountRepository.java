package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit-SQL persistence for {@code contract_technical_account}. */
@Repository
public class TechnicalAccountRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, account_number, period_start, period_end, currency,
            ceded_premium, ceded_losses, commission, other_charges, balance, status, created_at
            """;

    private static final RowMapper<ContractTechnicalAccount> MAPPER = (rs, rowNum) ->
            new ContractTechnicalAccount(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("contract_id", UUID.class),
                    rs.getString("account_number"),
                    rs.getObject("period_start", LocalDate.class),
                    rs.getObject("period_end", LocalDate.class),
                    rs.getString("currency"),
                    rs.getBigDecimal("ceded_premium"),
                    rs.getBigDecimal("ceded_losses"),
                    rs.getBigDecimal("commission"),
                    rs.getBigDecimal("other_charges"),
                    rs.getBigDecimal("balance"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbc;

    public TechnicalAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest period first, as required by the list endpoint. */
    public List<ContractTechnicalAccount> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_technical_account
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY period_start DESC, period_end DESC, created_at DESC, account_number
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractTechnicalAccount> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_technical_account
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    /**
     * The account recorded for one contract period, if any. A period bordereau
     * uses this to reuse the account instead of creating a duplicate.
     */
    public Optional<ContractTechnicalAccount> findForPeriod(UUID tenantId, UUID contractId,
                                                            LocalDate periodStart,
                                                            LocalDate periodEnd) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_technical_account
                 WHERE tenant_id = ? AND contract_id = ? AND period_start = ? AND period_end = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, periodStart, periodEnd)
                .stream().findFirst();
    }

    public ContractTechnicalAccount create(ContractTechnicalAccount account) {
        try {
            jdbc.update("""
                    INSERT INTO contract_technical_account (
                        id, tenant_id, contract_id, account_number, period_start, period_end,
                        currency, ceded_premium, ceded_losses, commission, other_charges,
                        balance, status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    account.id(), account.tenantId(), account.contractId(), account.accountNumber(),
                    account.periodStart(), account.periodEnd(), account.currency(),
                    account.cededPremium(), account.cededLosses(), account.commission(),
                    account.otherCharges(), account.balance(), account.status(),
                    Timestamp.from(account.createdAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_TECHNICAL_ACCOUNT_PERIOD",
                    "A technical account already exists for contract period "
                            + account.periodStart() + " to " + account.periodEnd(),
                    "periodStart", "Periods are unique per tenant and contract");
        }
        return findById(account.tenantId(), account.contractId(), account.id())
                .orElseThrow(() -> new ResourceNotFoundException("Technical account", account.id()));
    }

    /**
     * Compares and sets the status so two concurrent transitions cannot both win.
     * A zero-row update means the account disappeared or another request already
     * moved it; both are reported as a 404 or a 409 by the controller.
     */
    public ContractTechnicalAccount updateStatus(UUID tenantId, UUID contractId, UUID id,
                                                 TechnicalAccountStatus expected,
                                                 TechnicalAccountStatus target) {
        int updated = jdbc.update("""
                UPDATE contract_technical_account
                   SET status = ?
                 WHERE tenant_id = ? AND contract_id = ? AND id = ? AND status = ?
                """, target.name(), tenantId, contractId, id, expected.name());
        if (updated == 0) {
            ContractTechnicalAccount current = findById(tenantId, contractId, id)
                    .orElseThrow(() -> new ResourceNotFoundException("Technical account", id));
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Technical-account status cannot move from " + current.status()
                            + " to " + target,
                    "status", "Allowed transitions: DRAFT -> ISSUED -> SETTLED");
        }
        return findById(tenantId, contractId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Technical account", id));
    }
}
