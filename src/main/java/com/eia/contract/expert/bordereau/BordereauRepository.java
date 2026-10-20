package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit-SQL persistence for the period-bordereau rows of
 * {@code contract_bordereau}.
 *
 * <p>The table is shared with the legacy ingest header. Every query here filters
 * on {@code period_start IS NOT NULL}, so the two APIs see disjoint row sets and
 * the period lifecycle cannot accidentally process or list an ingest header.</p>
 */
@Repository
public class BordereauRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, bordereau_number, period_start, period_end,
            received_date, currency, premium_amount, loss_amount, commission_amount,
            other_charges_amount, line_count, notes, status, technical_account_id, created_at
            """;

    private static final RowMapper<ContractBordereau> MAPPER = (rs, rowNum) ->
            new ContractBordereau(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("contract_id", UUID.class),
                    rs.getString("bordereau_number"),
                    rs.getObject("period_start", LocalDate.class),
                    rs.getObject("period_end", LocalDate.class),
                    rs.getObject("received_date", LocalDate.class),
                    rs.getString("currency"),
                    rs.getBigDecimal("premium_amount"),
                    rs.getBigDecimal("loss_amount"),
                    rs.getBigDecimal("commission_amount"),
                    rs.getBigDecimal("other_charges_amount"),
                    rs.getInt("line_count"),
                    rs.getString("notes"),
                    rs.getString("status"),
                    rs.getObject("technical_account_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbc;

    public BordereauRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest period first, as required by the list endpoint. */
    public List<ContractBordereau> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_bordereau
                 WHERE tenant_id = ? AND contract_id = ? AND period_start IS NOT NULL
                 ORDER BY period_start DESC, period_end DESC, created_at DESC, bordereau_number
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractBordereau> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_bordereau
                 WHERE tenant_id = ? AND contract_id = ? AND id = ? AND period_start IS NOT NULL
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractBordereau create(ContractBordereau bordereau) {
        try {
            jdbc.update("""
                    INSERT INTO contract_bordereau (
                        id, tenant_id, contract_id, bordereau_number, period_start, period_end,
                        received_date, currency, premium_amount, loss_amount, commission_amount,
                        other_charges_amount, line_count, notes, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    bordereau.id(), bordereau.tenantId(), bordereau.contractId(),
                    bordereau.bordereauNumber(), bordereau.periodStart(), bordereau.periodEnd(),
                    bordereau.receivedDate(), bordereau.currency(), bordereau.premiumAmount(),
                    bordereau.lossAmount(), bordereau.commissionAmount(),
                    bordereau.otherChargesAmount(), bordereau.lineCount(), bordereau.notes(),
                    bordereau.status(), Timestamp.from(bordereau.createdAt()),
                    Timestamp.from(bordereau.createdAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_BORDEREAU_PERIOD",
                    "A bordereau already exists for contract period "
                            + bordereau.periodStart() + " to " + bordereau.periodEnd(),
                    "periodStart", "Periods are unique per tenant and contract");
        }
        return findById(bordereau.tenantId(), bordereau.contractId(), bordereau.id())
                .orElseThrow(() -> new ResourceNotFoundException("Bordereau", bordereau.id()));
    }

    /**
     * Compares and sets the status so two concurrent transitions cannot both win.
     * A zero-row update means the bordereau disappeared or another request
     * already moved it; both are reported as a 404 or a 409 by the controller.
     */
    public ContractBordereau updateStatus(UUID tenantId, UUID contractId, UUID id,
                                          BordereauStatus expected, BordereauStatus target,
                                          String notes, UUID technicalAccountId) {
        int updated = jdbc.update("""
                UPDATE contract_bordereau
                   SET status = ?,
                       notes = COALESCE(?, notes),
                       technical_account_id = ?,
                       updated_at = ?
                 WHERE tenant_id = ? AND contract_id = ? AND id = ? AND status = ?
                   AND period_start IS NOT NULL
                """, target.name(), notes, technicalAccountId, Timestamp.from(Instant.now()),
                tenantId, contractId, id, expected.name());
        if (updated == 0) {
            ContractBordereau current = findById(tenantId, contractId, id)
                    .orElseThrow(() -> new ResourceNotFoundException("Bordereau", id));
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Bordereau status cannot move from " + current.status() + " to " + target,
                    "status", "Allowed transitions: RECEIVED -> PROCESSED or RECEIVED -> FAILED");
        }
        return findById(tenantId, contractId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Bordereau", id));
    }
}
