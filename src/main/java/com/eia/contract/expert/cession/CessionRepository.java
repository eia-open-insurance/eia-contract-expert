package com.eia.contract.expert.cession;

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

/** Explicit-SQL persistence for {@code contract_cession}. */
@Repository
public class CessionRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, layer_id, cession_number, source_vertical, source_type,
            policy_ref, claim_ref, cession_type, cession_pct, ceded_premium_amount,
            ceded_loss_amount, currency, effective_from, effective_to, treaty_year, status,
            bordereau_id, recorded_at, version, created_at, updated_at
            """;

    private static final RowMapper<ContractCession> MAPPER = (rs, rowNum) -> new ContractCession(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getObject("layer_id", UUID.class),
            rs.getString("cession_number"),
            rs.getString("source_vertical"),
            rs.getString("source_type"),
            rs.getString("policy_ref"),
            rs.getString("claim_ref"),
            rs.getString("cession_type"),
            rs.getBigDecimal("cession_pct"),
            rs.getBigDecimal("ceded_premium_amount"),
            rs.getBigDecimal("ceded_loss_amount"),
            rs.getString("currency"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            (Integer) rs.getObject("treaty_year"),
            rs.getString("status"),
            rs.getObject("bordereau_id", UUID.class),
            rs.getTimestamp("recorded_at").toInstant(),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public CessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractCession> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_cession
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY recorded_at DESC, cession_number
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractCession> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_cession
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractCession create(ContractCession cession) {
        try {
            jdbc.update("""
                    INSERT INTO contract_cession (id, tenant_id, contract_id, layer_id, cession_number,
                                                  source_vertical, source_type, policy_ref, claim_ref,
                                                  cession_type, cession_pct, ceded_premium_amount,
                                                  ceded_loss_amount, currency, effective_from, effective_to,
                                                  treaty_year, status, bordereau_id, recorded_at,
                                                  version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    cession.id(), cession.tenantId(), cession.contractId(), cession.layerId(),
                    cession.cessionNumber(), cession.sourceVertical(), cession.sourceType(),
                    cession.policyRef(), cession.claimRef(), cession.cessionType(),
                    cession.cessionPct(), cession.cededPremiumAmount(), cession.cededLossAmount(),
                    cession.currency(), cession.effectiveFrom(), cession.effectiveTo(),
                    cession.treatyYear(), cession.status(), cession.bordereauId(),
                    Timestamp.from(cession.recordedAt()), Timestamp.from(cession.createdAt()),
                    Timestamp.from(cession.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_CESSION_NUMBER",
                    "Cession " + cession.cessionNumber() + " already exists on this contract",
                    "cessionNumber", "Cession numbers are unique per contract");
        }
        return findById(cession.tenantId(), cession.contractId(), cession.id())
                .orElseThrow(() -> new ResourceNotFoundException("Cession", cession.id()));
    }

    /**
     * Sum of active cession percentages already recorded for the same source,
     * used by SPEC CTR-05.
     */
    public BigDecimal activeShareForSource(UUID tenantId, UUID contractId, String sourceType,
                                           String policyRef, String claimRef, Integer treatyYear) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cession_pct), 0)
                  FROM contract_cession
                 WHERE tenant_id = ? AND contract_id = ?
                   AND status IN ('RECORDED', 'CONFIRMED')
                   AND source_type = ?
                   AND policy_ref IS NOT DISTINCT FROM ?
                   AND claim_ref IS NOT DISTINCT FROM ?
                   AND treaty_year IS NOT DISTINCT FROM ?
                """, BigDecimal.class, tenantId, contractId, sourceType, policyRef, claimRef, treatyYear);
        return total == null ? BigDecimal.ZERO : total;
    }
}
