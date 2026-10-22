package com.eia.contract.expert.cashcall;

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

/**
 * Explicit-SQL persistence for {@code contract_cash_call}.
 *
 * <p>Every query is scoped by tenant and contract, which is the contract wall.
 * The unique key {@code (tenant_id, contract_id, call_number)} and the
 * {@code period_start < period_end} / {@code amount > 0} check constraints are
 * the database-level enforcement of the cash-call invariants; the Java checks
 * mirror them so callers get a structured error instead of a SQL error.</p>
 */
@Repository
public class CashCallRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, call_number, period_start, period_end, amount,
            currency, due_date, status, notes, settlement_reference, created_at
            """;

    private static final RowMapper<ContractCashCall> MAPPER = (rs, rowNum) ->
            new ContractCashCall(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("contract_id", UUID.class),
                    rs.getString("call_number"),
                    rs.getObject("period_start", LocalDate.class),
                    rs.getObject("period_end", LocalDate.class),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getString("status"),
                    rs.getString("notes"),
                    rs.getString("settlement_reference"),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbc;

    public CashCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest call first, as required by the list endpoint. */
    public List<ContractCashCall> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_cash_call
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY created_at DESC, call_number DESC
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractCashCall> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_cash_call
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractCashCall create(ContractCashCall cashCall) {
        try {
            jdbc.update("""
                    INSERT INTO contract_cash_call (
                        id, tenant_id, contract_id, call_number, period_start, period_end,
                        amount, currency, due_date, status, notes, settlement_reference,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    cashCall.id(), cashCall.tenantId(), cashCall.contractId(),
                    cashCall.callNumber(), cashCall.periodStart(), cashCall.periodEnd(),
                    cashCall.amount(), cashCall.currency(), cashCall.dueDate(),
                    cashCall.status(), cashCall.notes(), cashCall.settlementReference(),
                    Timestamp.from(cashCall.createdAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_CASH_CALL_NUMBER",
                    "Cash call " + cashCall.callNumber()
                            + " already exists for this contract",
                    "callNumber", "Call numbers are unique per tenant and contract");
        }
        return findById(cashCall.tenantId(), cashCall.contractId(), cashCall.id())
                .orElseThrow(() -> new ResourceNotFoundException("Cash call", cashCall.id()));
    }

    /**
     * Compares and sets the status so two concurrent transitions cannot both win.
     * A zero-row update means the cash call disappeared or another request already
     * moved it; both are reported as a 404 or a 409 by the controller. The
     * settlement reference is written only on {@code SETTLED}; issue and
     * cancellation ignore it.
     */
    public ContractCashCall updateStatus(UUID tenantId, UUID contractId, UUID id,
                                        CashCallStatus expected, CashCallStatus target,
                                        String settlementReference) {
        String storedReference = target == CashCallStatus.SETTLED ? settlementReference : null;
        int updated = jdbc.update("""
                UPDATE contract_cash_call
                   SET status = ?, settlement_reference = ?
                 WHERE tenant_id = ? AND contract_id = ? AND id = ? AND status = ?
                """, target.name(), storedReference, tenantId, contractId, id, expected.name());
        if (updated == 0) {
            ContractCashCall current = findById(tenantId, contractId, id)
                    .orElseThrow(() -> new ResourceNotFoundException("Cash call", id));
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Cash-call status cannot move from " + current.status() + " to " + target,
                    "status", "Allowed transitions: REQUESTED -> ISSUED or REQUESTED -> CANCELLED; "
                    + "ISSUED -> SETTLED");
        }
        return findById(tenantId, contractId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Cash call", id));
    }
}
