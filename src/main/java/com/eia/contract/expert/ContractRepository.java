package com.eia.contract.expert;

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
 * Explicit-SQL persistence for the contract aggregate.
 *
 * <p>The unique key {@code (tenant_id, contract_number)} and the
 * {@code effective_date <= expiration_date} check constraint are the database-level
 * enforcement of the SPEC §11.3.4 invariants; the Java checks mirror them so callers
 * get a structured error instead of a SQL error.</p>
 */
@Repository
public class ContractRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_number, contract_type, cedent_party_id, reinsurer_party_id,
            status, effective_date, expiration_date, currency, version, created_at, updated_at,
            bound_at, bound_by, commuted_at, closed_at
            """;

    private static final RowMapper<Contract> MAPPER = (rs, rowNum) -> new Contract(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("contract_number"),
            rs.getString("contract_type"),
            rs.getObject("cedent_party_id", UUID.class),
            rs.getObject("reinsurer_party_id", UUID.class),
            rs.getString("status"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("expiration_date", LocalDate.class),
            rs.getString("currency"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            instant(rs, "bound_at"),
            rs.getObject("bound_by", UUID.class),
            instant(rs, "commuted_at"),
            instant(rs, "closed_at")
    );

    private final JdbcTemplate jdbc;

    public ContractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Contract> findAll(UUID tenantId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract
                 WHERE tenant_id = ?
                 ORDER BY effective_date DESC, contract_number
                """.formatted(COLUMNS), MAPPER, tenantId);
    }

    public Optional<Contract> findById(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract
                 WHERE tenant_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, id).stream().findFirst();
    }

    /** Row lock used by bind and commutation so the checks and the write are atomic. */
    public Optional<Contract> findByIdForUpdate(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract
                 WHERE tenant_id = ? AND id = ?
                   FOR UPDATE
                """.formatted(COLUMNS), MAPPER, tenantId, id).stream().findFirst();
    }

    public Contract create(CreateContractRequest request, ContractType contractType, ContractStatus status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO contract (id, tenant_id, contract_number, contract_type, cedent_party_id,
                                          reinsurer_party_id, status, effective_date, expiration_date,
                                          currency, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    id, request.tenantId(), request.contractNumber(), contractType.name(),
                    request.cedentPartyId(), request.reinsurerPartyId(), status.name(),
                    request.effectiveDate(), request.expirationDate(), request.currency(),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new DuplicateContractNumberException(request.contractNumber());
        }
        return findById(request.tenantId(), id).orElseThrow();
    }

    public Contract updateStatus(UUID tenantId, UUID id, ContractStatus target, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE contract
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, target.name(), Timestamp.from(Instant.now()), tenantId, id, expectedVersion);
        if (updated == 0) {
            throw notUpdated(tenantId, id);
        }
        return findById(tenantId, id).orElseThrow();
    }

    /** PLACED -> BOUND with the approval evidence required by SPEC CTR-15. */
    public Contract bind(UUID tenantId, UUID id, UUID approvedBy, int expectedVersion) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE contract
                   SET status = 'BOUND', bound_at = ?, bound_by = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'PLACED'
                """, Timestamp.from(now), approvedBy, Timestamp.from(now), tenantId, id, expectedVersion);
        if (updated == 0) {
            throw notUpdated(tenantId, id);
        }
        return findById(tenantId, id).orElseThrow();
    }

    /**
     * Records the status consequence of an executed commutation. A full commutation
     * walks the legal BOUND/IN_FORCE -> COMMUTED -> CLOSED path and therefore sets
     * both timestamps.
     */
    public Contract applyCommutation(UUID tenantId, UUID id, ContractStatus target,
                                     Instant commutedAt, Instant closedAt, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE contract
                   SET status = ?,
                       commuted_at = COALESCE(commuted_at, ?),
                       closed_at = COALESCE(?, closed_at),
                       version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, target.name(), Timestamp.from(commutedAt),
                closedAt == null ? null : Timestamp.from(closedAt),
                Timestamp.from(Instant.now()), tenantId, id, expectedVersion);
        if (updated == 0) {
            throw notUpdated(tenantId, id);
        }
        return findById(tenantId, id).orElseThrow();
    }

    private RuntimeException notUpdated(UUID tenantId, UUID id) {
        if (findById(tenantId, id).isEmpty()) {
            return new ContractNotFoundException(id);
        }
        return new ConcurrentContractUpdateException(id);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
