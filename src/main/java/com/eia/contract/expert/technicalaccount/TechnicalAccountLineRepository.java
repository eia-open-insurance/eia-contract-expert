package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit-SQL persistence for {@code contract_technical_account_line}.
 *
 * <p>Every query is scoped by tenant and account, which keeps the contract wall
 * and prevents a line of another tenant or account from being read or removed.
 * The database enforces the line-type domain, the positive amount, and the
 * {@code ON DELETE CASCADE} link to the account; the Java rules mirror them so
 * callers receive a structured error.</p>
 */
@Repository
public class TechnicalAccountLineRepository {

    private static final String COLUMNS = """
            id, tenant_id, account_id, line_type, reference, description, amount, created_at
            """;

    private static final RowMapper<ContractTechnicalAccountLine> MAPPER = (rs, rowNum) ->
            new ContractTechnicalAccountLine(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    TechnicalAccountLineType.valueOf(rs.getString("line_type")),
                    rs.getString("reference"),
                    rs.getString("description"),
                    rs.getBigDecimal("amount"),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbc;

    public TechnicalAccountLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insertion order is the order the account detail was entered. */
    public List<ContractTechnicalAccountLine> findAll(UUID tenantId, UUID accountId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_technical_account_line
                 WHERE tenant_id = ? AND account_id = ?
                 ORDER BY created_at, id
                """.formatted(COLUMNS), MAPPER, tenantId, accountId);
    }

    public Optional<ContractTechnicalAccountLine> findById(UUID tenantId, UUID accountId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_technical_account_line
                 WHERE tenant_id = ? AND account_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, accountId, id).stream().findFirst();
    }

    public ContractTechnicalAccountLine create(ContractTechnicalAccountLine line) {
        jdbc.update("""
                INSERT INTO contract_technical_account_line (
                    id, tenant_id, account_id, line_type, reference, description, amount, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                line.id(), line.tenantId(), line.accountId(), line.lineType().name(),
                line.reference(), line.description(), line.amount(),
                Timestamp.from(line.createdAt()));
        return findById(line.tenantId(), line.accountId(), line.id())
                .orElseThrow(() -> new ResourceNotFoundException("Technical account line", line.id()));
    }

    /** @return true when the line existed for this tenant and account */
    public boolean delete(UUID tenantId, UUID accountId, UUID id) {
        return jdbc.update("""
                DELETE FROM contract_technical_account_line
                 WHERE tenant_id = ? AND account_id = ? AND id = ?
                """, tenantId, accountId, id) > 0;
    }

    /**
     * Totals by line type for one account. An empty list means the account has
     * no lines, which keeps the legacy issue path (no line detail) valid.
     */
    public List<LineTotal> totals(UUID tenantId, UUID accountId) {
        return jdbc.query("""
                SELECT line_type, SUM(amount) AS total
                  FROM contract_technical_account_line
                 WHERE tenant_id = ? AND account_id = ?
                 GROUP BY line_type
                 ORDER BY line_type
                """, (rs, rowNum) -> new LineTotal(
                        rs.getString("line_type"), rs.getBigDecimal("total")),
                tenantId, accountId);
    }

    public record LineTotal(String lineType, BigDecimal amount) {}
}
