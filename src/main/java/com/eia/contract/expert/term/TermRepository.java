package com.eia.contract.expert.term;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Explicit-SQL persistence for {@code contract_term}. */
@Repository
public class TermRepository {

    private static final String COLUMNS = """
            id, tenant_id, contract_id, term_code, term_value, value_type,
            effective_from, effective_to, source_clause, created_at, updated_at
            """;

    private static final RowMapper<ContractTerm> MAPPER = (rs, rowNum) -> new ContractTerm(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getString("term_code"),
            rs.getString("term_value"),
            rs.getString("value_type"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("source_clause"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public TermRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractTerm> findAll(UUID tenantId, UUID contractId) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_term
                 WHERE tenant_id = ? AND contract_id = ?
                 ORDER BY term_code
                """.formatted(COLUMNS), MAPPER, tenantId, contractId);
    }

    public Optional<ContractTerm> findById(UUID tenantId, UUID contractId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM contract_term
                 WHERE tenant_id = ? AND contract_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, contractId, id).stream().findFirst();
    }

    public Set<String> codes(UUID tenantId, UUID contractId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT term_code
                  FROM contract_term
                 WHERE tenant_id = ? AND contract_id = ?
                """, String.class, tenantId, contractId));
    }

    public ContractTerm create(ContractTerm term) {
        try {
            jdbc.update("""
                    INSERT INTO contract_term (id, tenant_id, contract_id, term_code, term_value,
                                               value_type, effective_from, effective_to, source_clause,
                                               created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    term.id(), term.tenantId(), term.contractId(), term.termCode(), term.termValue(),
                    term.valueType(), term.effectiveFrom(), term.effectiveTo(), term.sourceClause(),
                    Timestamp.from(term.createdAt()), Timestamp.from(term.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw new ContractConflictException("DUPLICATE_TERM_CODE",
                    "Term " + term.termCode() + " is already recorded on this contract",
                    "termCode", "A term code is unique per contract");
        }
        return findById(term.tenantId(), term.contractId(), term.id())
                .orElseThrow(() -> new ResourceNotFoundException("Term", term.id()));
    }
}
