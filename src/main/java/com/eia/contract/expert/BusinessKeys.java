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
}
