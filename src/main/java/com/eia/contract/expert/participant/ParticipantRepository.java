package com.eia.contract.expert.participant;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit-SQL persistence for {@code contract_participant}. */
@Repository
public class ParticipantRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, layer_id, party_id, participant_role, share_pct,
            signed_line_pct, order_number, lead_flag, capacity_amount, security_rating,
            collateral_required, signature_status, signed_at, authorization_ref,
            effective_from, effective_to, status, created_at, updated_at
            """;

    private static final RowMapper<ContractParticipant> MAPPER = (rs, rowNum) -> new ContractParticipant(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getObject("layer_id", UUID.class),
            rs.getObject("party_id", UUID.class),
            rs.getString("participant_role"),
            rs.getBigDecimal("share_pct"),
            rs.getBigDecimal("signed_line_pct"),
            (Integer) rs.getObject("order_number"),
            rs.getBoolean("lead_flag"),
            rs.getBigDecimal("capacity_amount"),
            rs.getString("security_rating"),
            rs.getBoolean("collateral_required"),
            rs.getString("signature_status"),
            instant(rs, "signed_at"),
            rs.getString("authorization_ref"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ParticipantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractParticipant> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_participant
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY layer_id NULLS FIRST, COALESCE(order_number, 999), party_id
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public List<ContractParticipant> findActive(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_participant
                 WHERE tenant_id = ? AND contract_id = ? AND status = 'ACTIVE'
                 ORDER BY layer_id NULLS FIRST, COALESCE(order_number, 999), party_id
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractParticipant> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_participant
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    /** Sum of active risk-bearing shares for one layer (or contract level when null). */
    public BigDecimal activeShareTotal(UUID tenantId, UUID contractId, UUID layerId) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(share_pct), 0)
                  FROM contract_participant
                 WHERE tenant_id = ? AND contract_id = ? AND status = 'ACTIVE'
                   AND layer_id IS NOT DISTINCT FROM ?
                   AND participant_role IN ('REINSURER', 'LEAD', 'FOLLOW', 'FRONTING', 'RETROCESSIONAIRE')
                """, BigDecimal.class, tenantId, contractId, layerId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /** Active risk-bearing share totals, grouped by layer (null = contract level). */
    public List<ShareTotal> activeShareTotals(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT layer_id, SUM(share_pct) AS total, COUNT(*) AS participants
                  FROM contract_participant
                 WHERE tenant_id = ? AND contract_id = ? AND status = 'ACTIVE'
                   AND participant_role IN ('REINSURER', 'LEAD', 'FOLLOW', 'FRONTING', 'RETROCESSIONAIRE')
                 GROUP BY layer_id
                 ORDER BY layer_id NULLS FIRST
                """, (rs, rowNum) -> new ShareTotal(
                rs.getObject("layer_id", UUID.class),
                rs.getBigDecimal("total"),
                rs.getInt("participants")), tenantId, contractId);
    }

    public ContractParticipant create(ContractParticipant participant) {
        try {
            jdbc.update("""
                    INSERT INTO contract_participant (id, tenant_id, contract_id, layer_id, party_id,
                                                      participant_role, share_pct, signed_line_pct,
                                                      order_number, lead_flag, capacity_amount,
                                                      security_rating, collateral_required,
                                                      signature_status, signed_at, authorization_ref,
                                                      effective_from, effective_to, status,
                                                      created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    participant.id(), participant.tenantId(), participant.contractId(),
                    participant.layerId(), participant.partyId(), participant.participantRole(),
                    participant.sharePct(), participant.signedLinePct(), participant.orderNumber(),
                    participant.leadFlag(), participant.capacityAmount(), participant.securityRating(),
                    participant.collateralRequired(), participant.signatureStatus(),
                    participant.signedAt() == null ? null : Timestamp.from(participant.signedAt()),
                    participant.authorizationRef(), participant.effectiveFrom(),
                    participant.effectiveTo(), participant.status(),
                    Timestamp.from(participant.createdAt()), Timestamp.from(participant.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_PARTICIPANT",
                    "An active participation already exists for this party and role on the layer",
                    "partyId", "SPEC 11.3.4 unique active participation");
        }
        return findById(participant.tenantId(), participant.contractId(), participant.id())
                .orElseThrow(() -> new ResourceNotFoundException("Participant", participant.id()));
    }

    /** A layer total row: {@code layerId} is null for contract-level participations. */
    public record ShareTotal(UUID layerId, BigDecimal total, int participants) {}

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
