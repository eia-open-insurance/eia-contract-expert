package com.eia.contract.expert.security;

import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit-SQL persistence for {@code contract_security}. */
@Repository
public class SecurityRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, security_type, required_amount, held_amount, currency,
            provider_party_id, beneficiary_party_id, effective_from, effective_to, expiry_date,
            status, collateral_quality, document_id, release_conditions, approval_ref, approved_at,
            created_at, updated_at
            """;

    private static final RowMapper<ContractSecurity> MAPPER = (rs, rowNum) -> new ContractSecurity(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getString("security_type"),
            rs.getBigDecimal("required_amount"),
            rs.getBigDecimal("held_amount"),
            rs.getString("currency"),
            rs.getObject("provider_party_id", UUID.class),
            rs.getObject("beneficiary_party_id", UUID.class),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("collateral_quality"),
            rs.getObject("document_id", UUID.class),
            rs.getString("release_conditions"),
            rs.getString("approval_ref"),
            instant(rs, "approved_at"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public SecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest collateral record first, which is the public security API's order. */
    public List<ContractSecurity> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_security
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY created_at DESC, id DESC
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractSecurity> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_security
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractSecurity create(ContractSecurity security) {
        jdbc.update("""
                INSERT INTO contract_security (id, tenant_id, contract_id, security_type,
                                               required_amount, held_amount, currency,
                                               provider_party_id, beneficiary_party_id,
                                               effective_from, effective_to, expiry_date, status,
                                               collateral_quality, document_id, release_conditions,
                                               approval_ref, approved_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                security.id(), security.tenantId(), security.contractId(), security.securityType(),
                security.requiredAmount(), security.heldAmount(), security.currency(),
                security.providerPartyId(), security.beneficiaryPartyId(), security.effectiveFrom(),
                security.effectiveTo(), security.expiryDate(), security.status(),
                security.collateralQuality(), security.documentId(), security.releaseConditions(),
                security.approvalRef(),
                security.approvedAt() == null ? null : Timestamp.from(security.approvedAt()),
                Timestamp.from(security.createdAt()), Timestamp.from(security.updatedAt()));
        return findById(security.tenantId(), security.contractId(), security.id())
                .orElseThrow(() -> new ResourceNotFoundException("Security", security.id()));
    }

    /** Releases every outstanding security row when a commutation executes (SPEC 11.3.8 step 6). */
    public int releaseAll(UUID tenantId, UUID contractId, LocalDate releaseDate) {
        return jdbc.update("""
                UPDATE contract_security
                   SET status = 'RELEASED',
                       effective_to = GREATEST(?, effective_from + 1),
                       updated_at = ?
                 WHERE tenant_id = ? AND contract_id = ?
                   AND status NOT IN ('RELEASED', 'EXPIRED')
                """, releaseDate, Timestamp.from(Instant.now()), tenantId, contractId);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
