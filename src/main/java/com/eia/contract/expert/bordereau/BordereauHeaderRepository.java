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

/** Explicit-SQL persistence for {@code contract_bordereau}. */
@Repository
public class BordereauHeaderRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, bordereau_number, bordereau_type, period_from, period_to,
            received_at, source_document_id, source_format, source_reference, status, line_count,
            valid_line_count, exception_count, total_amount, currency, checksum, processed_at,
            notes, created_at, updated_at
            """;

    private static final RowMapper<ContractBordereauHeader> MAPPER = (rs, rowNum) -> new ContractBordereauHeader(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getString("bordereau_number"),
            rs.getString("bordereau_type"),
            rs.getObject("period_from", LocalDate.class),
            rs.getObject("period_to", LocalDate.class),
            instant(rs, "received_at"),
            rs.getObject("source_document_id", UUID.class),
            rs.getString("source_format"),
            rs.getString("source_reference"),
            rs.getString("status"),
            rs.getInt("line_count"),
            (Integer) rs.getObject("valid_line_count"),
            (Integer) rs.getObject("exception_count"),
            rs.getBigDecimal("total_amount"),
            rs.getString("currency"),
            rs.getString("checksum"),
            instant(rs, "processed_at"),
            rs.getString("notes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public BordereauHeaderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractBordereauHeader> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_bordereau
                 WHERE tenant_id = ? AND contract_id = ? AND period_start IS NULL
                 ORDER BY period_from DESC, bordereau_number
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractBordereauHeader> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_bordereau
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractBordereauHeader get(UUID tenantId, UUID contractId, UUID id) {
        return findById(tenantId, contractId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Bordereau", id));
    }

    public ContractBordereauHeader create(ContractBordereauHeader bordereau) {
        try {
            jdbc.update("""
                    INSERT INTO contract_bordereau (id, tenant_id, contract_id, bordereau_number,
                                                    bordereau_type, period_from, period_to, received_at,
                                                    source_document_id, source_format, source_reference,
                                                    status, line_count, valid_line_count, exception_count,
                                                    total_amount, currency, checksum, notes,
                                                    created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    bordereau.id(), bordereau.tenantId(), bordereau.contractId(),
                    bordereau.bordereauNumber(), bordereau.bordereauType(), bordereau.periodFrom(),
                    bordereau.periodTo(), Timestamp.from(bordereau.receivedAt()),
                    bordereau.sourceDocumentId(), bordereau.sourceFormat(),
                    bordereau.sourceReference(), bordereau.status(), bordereau.lineCount(),
                    bordereau.validLineCount(), bordereau.exceptionCount(),
                    bordereau.totalAmount(), bordereau.currency(), bordereau.checksum(),
                    bordereau.notes(), Timestamp.from(bordereau.createdAt()),
                    Timestamp.from(bordereau.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_BORDEREAU_NUMBER",
                    "Bordereau " + bordereau.bordereauNumber() + " already exists on this contract",
                    "bordereauNumber", "Bordereau numbers are unique per contract");
        }
        return get(bordereau.tenantId(), bordereau.contractId(), bordereau.id());
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
