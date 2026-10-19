package com.eia.contract.expert.layer;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit-SQL persistence for {@code contract_layer}. */
@Repository
public class LayerRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, section_id, layer_number, layer_type,
            attachment_amount, limit_amount, currency, limit_basis, aggregate_limit_amount,
            reinstatement_count, reinstatement_type, note, effective_from, effective_to,
            status, created_at, updated_at
            """;

    private static final RowMapper<ContractLayer> MAPPER = (rs, rowNum) -> new ContractLayer(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getObject("section_id", UUID.class),
            rs.getInt("layer_number"),
            rs.getString("layer_type"),
            rs.getBigDecimal("attachment_amount"),
            rs.getBigDecimal("limit_amount"),
            rs.getString("currency"),
            rs.getString("limit_basis"),
            rs.getBigDecimal("aggregate_limit_amount"),
            (Integer) rs.getObject("reinstatement_count"),
            rs.getString("reinstatement_type"),
            rs.getString("note"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public LayerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractLayer> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_layer
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY layer_number
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    /** Layers that participate in the tower: quoted, bound, and in force. */
    public List<ContractLayer> findLive(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_layer
                 WHERE tenant_id = ? AND contract_id = ?
                   AND status IN ('QUOTED', 'BOUND', 'IN_FORCE')
                 ORDER BY attachment_amount, layer_number
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractLayer> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_layer
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public ContractLayer get(UUID tenantId, UUID contractId, UUID id) {
        return findById(tenantId, contractId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Layer", id));
    }

    public ContractLayer create(ContractLayer layer) {
        try {
            jdbc.update("""
                    INSERT INTO contract_layer (id, tenant_id, contract_id, section_id, layer_number,
                                                layer_type, attachment_amount, limit_amount, currency,
                                                limit_basis, aggregate_limit_amount, reinstatement_count,
                                                reinstatement_type, note, effective_from, effective_to,
                                                status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    layer.id(), layer.tenantId(), layer.contractId(), layer.sectionId(),
                    layer.layerNumber(), layer.layerType(), layer.attachmentAmount(),
                    layer.limitAmount(), layer.currency(), layer.limitBasis(),
                    layer.aggregateLimitAmount(), layer.reinstatementCount(),
                    layer.reinstatementType(), layer.note(), layer.effectiveFrom(),
                    layer.effectiveTo(), layer.status(), Timestamp.from(layer.createdAt()),
                    Timestamp.from(layer.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_LAYER_NUMBER",
                    "Layer number " + layer.layerNumber() + " already exists on this contract",
                    "layerNumber", "Layer numbers are unique per contract");
        }
        return get(layer.tenantId(), layer.contractId(), layer.id());
    }

    /** Total capacity of the live tower, expressed in the contract currency. */
    public BigDecimal capacity(UUID tenantId, UUID contractId) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(aggregate_limit_amount, limit_amount)), 0)
                  FROM contract_layer
                 WHERE tenant_id = ? AND contract_id = ?
                   AND status IN ('QUOTED', 'BOUND', 'IN_FORCE')
                """, BigDecimal.class, tenantId, contractId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
