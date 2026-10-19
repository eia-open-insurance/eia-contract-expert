CREATE TABLE IF NOT EXISTS contract (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_number VARCHAR(32) NOT NULL,
    contract_type VARCHAR(48) NOT NULL,
    cedent_party_id UUID NOT NULL,
    reinsurer_party_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    effective_date DATE NOT NULL,
    expiration_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_number UNIQUE (tenant_id, contract_number),
    CONSTRAINT ck_contract_date_range CHECK (effective_date <= expiration_date),
    CONSTRAINT ck_contract_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_status CHECK (status IN (
        'DRAFT', 'QUOTED', 'PLACED', 'BOUND', 'SIGNED', 'IN_FORCE', 'ENDORSED',
        'EXPIRED', 'CANCELLED', 'COMMUTED', 'RUNOFF', 'CLOSED', 'VOID'
    ))
);

CREATE INDEX IF NOT EXISTS ix_contract_tenant_status ON contract (tenant_id, status);
