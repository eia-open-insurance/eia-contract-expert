package com.eia.contract.expert.commutation;

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

/** Explicit-SQL persistence for {@code contract_commutation}. */
@Repository
public class CommutationRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, commutation_number, commutation_type, effective_date,
            valuation_date, agreed_amount, currency, basis, status, release_document_id,
            release_date, payment_id, savings_amount, approved_by, approved_at, executed_at,
            created_at, updated_at
            """;

    private static final RowMapper<ContractCommutation> MAPPER = (rs, rowNum) -> new ContractCommutation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getString("commutation_number"),
            rs.getString("commutation_type"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("valuation_date", LocalDate.class),
            rs.getBigDecimal("agreed_amount"),
            rs.getString("currency"),
            rs.getString("basis"),
            rs.getString("status"),
            rs.getObject("release_document_id", UUID.class),
            rs.getObject("release_date", LocalDate.class),
            rs.getObject("payment_id", UUID.class),
            rs.getBigDecimal("savings_amount"),
            rs.getObject("approved_by", UUID.class),
            instant(rs, "approved_at"),
            instant(rs, "executed_at"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public CommutationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractCommutation> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_commutation
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY created_at DESC
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractCommutation> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_commutation
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractCommutation get(UUID tenantId, UUID contractId, UUID id) {
        return findById(tenantId, contractId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Commutation", id));
    }

    public ContractCommutation create(ContractCommutation commutation) {
        try {
            jdbc.update("""
                    INSERT INTO contract_commutation (id, tenant_id, contract_id, commutation_number,
                                                     commutation_type, effective_date, valuation_date,
                                                     agreed_amount, currency, basis, status,
                                                     release_document_id, payment_id, savings_amount,
                                                     created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    commutation.id(), commutation.tenantId(), commutation.contractId(),
                    commutation.commutationNumber(), commutation.commutationType(),
                    commutation.effectiveDate(), commutation.valuationDate(),
                    commutation.agreedAmount(), commutation.currency(), commutation.basis(),
                    commutation.status(), commutation.releaseDocumentId(), commutation.paymentId(),
                    commutation.savingsAmount(), Timestamp.from(commutation.createdAt()),
                    Timestamp.from(commutation.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_COMMUTATION_NUMBER",
                    "Commutation " + commutation.commutationNumber() + " already exists on this contract",
                    "commutationNumber", "Commutation numbers are unique per contract");
        }
        return get(commutation.tenantId(), commutation.contractId(), commutation.id());
    }

    /** PROPOSED -> APPROVED. The state guard makes a duplicate approval a conflict. */
    public ContractCommutation approve(UUID tenantId, UUID contractId, UUID id, UUID approvedBy) {
        int updated = jdbc.update("""
                UPDATE contract_commutation
                   SET status = 'APPROVED', approved_by = ?, approved_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND contract_id = ? AND id = ? AND status = 'PROPOSED'
                """, approvedBy, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                tenantId, contractId, id);
        if (updated == 0) {
            ContractCommutation existing = get(tenantId, contractId, id);
            throw new ContractConflictException("COMMUTATION_NOT_PROPOSED",
                    "Commutation " + existing.commutationNumber() + " is " + existing.status()
                            + "; only a PROPOSED commutation can be approved",
                    "status", "Expected PROPOSED");
        }
        return get(tenantId, contractId, id);
    }

    /** APPROVED -> EXECUTED. Requires the release document to be attached. */
    public ContractCommutation execute(UUID tenantId, UUID contractId, UUID id,
                                       UUID releaseDocumentId, LocalDate releaseDate) {
        int updated = jdbc.update("""
                UPDATE contract_commutation
                   SET status = 'EXECUTED', release_document_id = ?, release_date = ?,
                       executed_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND contract_id = ? AND id = ? AND status = 'APPROVED'
                """, releaseDocumentId, releaseDate, Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()), tenantId, contractId, id);
        if (updated == 0) {
            ContractCommutation existing = get(tenantId, contractId, id);
            throw new ContractConflictException("COMMUTATION_NOT_APPROVED",
                    "Commutation " + existing.commutationNumber() + " is " + existing.status()
                            + "; it must be APPROVED before it can be executed",
                    "status", "Expected APPROVED");
        }
        return get(tenantId, contractId, id);
    }

    /** Attaches the release document while the commutation is still pending. */
    public void attachReleaseDocument(UUID tenantId, UUID contractId, UUID id, UUID releaseDocumentId) {
        jdbc.update("""
                UPDATE contract_commutation
                   SET release_document_id = COALESCE(release_document_id, ?), updated_at = ?
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """, releaseDocumentId, Timestamp.from(Instant.now()), tenantId, contractId, id);
    }

    public boolean hasExecuted(UUID tenantId, UUID contractId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contract_commutation
                 WHERE tenant_id = ? AND contract_id = ? AND status = 'EXECUTED'
                """, Integer.class, tenantId, contractId);
        return count != null && count > 0;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
