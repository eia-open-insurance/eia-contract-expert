package com.eia.contract.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Generates {@code {PREFIX}-{YEAR}-{SEQ}} business keys (SPEC 10.1) from a
 * per-tenant, per-prefix, per-year counter. The counter is advanced inside the
 * caller's transaction, so a rolled-back write does not burn a number and two
 * concurrent callers cannot collide.
 */
@Component
public class BusinessKeys {

    private final JdbcTemplate jdbc;

    public BusinessKeys(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(UUID tenantId, String prefix, int year) {
        Integer next = jdbc.queryForObject("""
                INSERT INTO contract_business_key (tenant_id, prefix, year, next_value)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (tenant_id, prefix, year)
                DO UPDATE SET next_value = contract_business_key.next_value + 1
                RETURNING next_value
                """, Integer.class, tenantId, prefix, year);
        return "%s-%d-%05d".formatted(prefix, year, next == null ? 1 : next);
    }

    /**
     * A portable upsert for callers whose tests run on H2, which does not accept a
     * conflict target in {@code ON CONFLICT}. The insert-if-absent and the locked
     * counter read run in the caller's transaction; the unique key plus
     * {@code SELECT ... FOR UPDATE} serialize concurrent generators for the same
     * key, so every caller receives a distinct number.
     *
     * <p>{@code year} is quoted because it is a reserved word in H2; the quoted
     * spelling resolves to the same lowercase column in PostgreSQL.</p>
     */
    public String nextPortable(UUID tenantId, String prefix, int year) {
        jdbc.update("""
                INSERT INTO contract_business_key (tenant_id, prefix, "year", next_value)
                VALUES (?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """, tenantId, prefix, year);
        Integer next = jdbc.queryForObject("""
                SELECT next_value + 1
                  FROM contract_business_key
                 WHERE tenant_id = ? AND prefix = ? AND "year" = ?
                   FOR UPDATE
                """, Integer.class, tenantId, prefix, year);
        jdbc.update("""
                UPDATE contract_business_key
                   SET next_value = ?
                 WHERE tenant_id = ? AND prefix = ? AND "year" = ?
                """, next, tenantId, prefix, year);
        return "%s-%d-%05d".formatted(prefix, year, next == null ? 1 : next);
    }
}
