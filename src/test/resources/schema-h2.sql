-- H2-compatible copy of the contract tables exercised by the component and
-- integration tests. The PostgreSQL schema.sql is authoritative. Differences:
-- JSON columns are TEXT instead of jsonb, PostgreSQL regex operators and partial
-- indexes are omitted, and the business-key counter is not needed because the
-- tests supply explicit cession and commutation numbers.

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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    bound_at TIMESTAMP WITH TIME ZONE,
    bound_by UUID,
    commuted_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_contract_number UNIQUE (tenant_id, contract_number),
    CONSTRAINT ck_contract_date_range CHECK (effective_date <= expiration_date),
    CONSTRAINT ck_contract_status CHECK (status IN (
        'DRAFT', 'QUOTED', 'PLACED', 'BOUND', 'SIGNED', 'IN_FORCE', 'ENDORSED',
        'EXPIRED', 'CANCELLED', 'COMMUTED', 'RUNOFF', 'CLOSED', 'VOID'
    ))
);

-- Portable business-key counter. `year` is a reserved word in H2 and must be
-- quoted here; the quoted spelling also resolves to the same lowercase column
-- in PostgreSQL.
CREATE TABLE IF NOT EXISTS contract_business_key (
    tenant_id UUID NOT NULL,
    prefix VARCHAR(8) NOT NULL,
    "year" INTEGER NOT NULL,
    next_value INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, prefix, "year")
);

CREATE TABLE IF NOT EXISTS contract_layer (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    section_id UUID,
    layer_number INTEGER NOT NULL,
    layer_type VARCHAR(32) NOT NULL,
    attachment_amount NUMERIC(19,4) NOT NULL,
    limit_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    limit_basis VARCHAR(24) NOT NULL,
    aggregate_limit_amount NUMERIC(19,4),
    reinstatement_count INTEGER,
    reinstatement_type VARCHAR(24),
    note VARCHAR(300),
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_contract_layer_number UNIQUE (tenant_id, contract_id, layer_number),
    CONSTRAINT ck_contract_layer_attachment CHECK (attachment_amount >= 0),
    CONSTRAINT ck_contract_layer_limit CHECK (limit_amount > 0),
    CONSTRAINT ck_contract_layer_aggregate CHECK (
        aggregate_limit_amount IS NULL OR aggregate_limit_amount >= limit_amount),
    CONSTRAINT ck_contract_layer_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_layer_status CHECK (status IN (
        'QUOTED', 'BOUND', 'IN_FORCE', 'EXHAUSTED', 'EXPIRED', 'CANCELLED'
    ))
);

CREATE TABLE IF NOT EXISTS contract_participant (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    layer_id UUID,
    party_id UUID NOT NULL,
    participant_role VARCHAR(24) NOT NULL,
    share_pct NUMERIC(9,6) NOT NULL,
    signed_line_pct NUMERIC(9,6),
    order_number INTEGER,
    lead_flag BOOLEAN NOT NULL DEFAULT FALSE,
    capacity_amount NUMERIC(19,4),
    security_rating VARCHAR(8),
    collateral_required BOOLEAN NOT NULL DEFAULT FALSE,
    signature_status VARCHAR(16) NOT NULL,
    signed_at TIMESTAMP WITH TIME ZONE,
    authorization_ref VARCHAR(64),
    effective_from DATE,
    effective_to DATE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_contract_participant_share CHECK (share_pct >= 0 AND share_pct <= 100),
    CONSTRAINT ck_contract_participant_signed CHECK (
        signed_line_pct IS NULL OR (signed_line_pct >= 0 AND signed_line_pct <= 100)),
    CONSTRAINT ck_contract_participant_effective CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_participant_status CHECK (status IN ('ACTIVE', 'ENDED', 'REPLACED')),
    CONSTRAINT ck_contract_participant_role CHECK (participant_role IN (
        'REINSURER', 'CEDENT', 'LEAD', 'FOLLOW', 'FRONTING', 'RETROCESSIONAIRE', 'BROKER'
    )),
    CONSTRAINT ck_contract_participant_signature CHECK (signature_status IN (
        'PENDING', 'SIGNED', 'DECLINED', 'WITHDRAWN'
    ))
);

CREATE TABLE IF NOT EXISTS contract_term (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    term_code VARCHAR(48) NOT NULL,
    term_value VARCHAR(400) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    source_clause VARCHAR(48),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_contract_term_code UNIQUE (tenant_id, contract_id, term_code),
    CONSTRAINT ck_contract_term_value_type CHECK (value_type IN ('MONEY', 'PERCENT', 'TEXT', 'DATE', 'INTEGER', 'BOOLEAN')),
    CONSTRAINT ck_contract_term_dates CHECK (effective_to IS NULL OR effective_from < effective_to)
);

CREATE TABLE IF NOT EXISTS contract_security (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    security_type VARCHAR(24) NOT NULL,
    required_amount NUMERIC(19,4) NOT NULL,
    held_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    provider_party_id UUID,
    beneficiary_party_id UUID,
    effective_from DATE NOT NULL,
    effective_to DATE,
    expiry_date DATE,
    status VARCHAR(16) NOT NULL,
    collateral_quality VARCHAR(24),
    document_id UUID,
    release_conditions VARCHAR(400),
    approval_ref VARCHAR(64),
    approved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_contract_security_amounts CHECK (required_amount >= 0 AND held_amount >= 0),
    CONSTRAINT ck_contract_security_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_security_expiry CHECK (expiry_date IS NULL OR expiry_date >= effective_from),
    CONSTRAINT ck_contract_security_status CHECK (status IN (
        'REQUIRED', 'OUTSTANDING', 'PARTIAL', 'RELEASED', 'EXPIRED', 'BREACH'
    )),
    CONSTRAINT ck_contract_security_type CHECK (security_type IN (
        'LETTER_OF_CREDIT', 'TRUST_FUND', 'FUNDS_WITHHELD', 'CASH_ADVANCE',
        'PARENT_GUARANTEE', 'BANK_GUARANTEE', 'SECURITIES'
    ))
);

CREATE TABLE IF NOT EXISTS contract_cession (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    layer_id UUID,
    cession_number VARCHAR(32) NOT NULL,
    source_vertical VARCHAR(16) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    policy_ref VARCHAR(64),
    claim_ref VARCHAR(64),
    cession_type VARCHAR(24) NOT NULL,
    cession_pct NUMERIC(9,6),
    ceded_premium_amount NUMERIC(19,4),
    ceded_loss_amount NUMERIC(19,4),
    currency CHAR(3) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    treaty_year INTEGER,
    status VARCHAR(16) NOT NULL,
    bordereau_id UUID,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_contract_cession_number UNIQUE (tenant_id, contract_id, cession_number),
    CONSTRAINT ck_contract_cession_book CHECK (source_vertical = 'ASSUMED'),
    CONSTRAINT ck_contract_cession_source CHECK (source_type IN ('POLICY', 'CLAIM', 'LOSS_ADVICE')),
    CONSTRAINT ck_contract_cession_pct CHECK (cession_pct IS NULL OR (cession_pct >= 0 AND cession_pct <= 100)),
    CONSTRAINT ck_contract_cession_amounts CHECK (
        (ceded_premium_amount IS NULL OR ceded_premium_amount >= 0)
        AND (ceded_loss_amount IS NULL OR ceded_loss_amount >= 0)
        AND (ceded_premium_amount IS NOT NULL OR ceded_loss_amount IS NOT NULL)),
    CONSTRAINT ck_contract_cession_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_cession_status CHECK (status IN (
        'RECORDED', 'CONFIRMED', 'REVERSED', 'DISPUTED'
    )),
    CONSTRAINT ck_contract_cession_type CHECK (cession_type IN (
        'QUOTA_SHARE', 'SURPLUS', 'FACULTATIVE', 'EXCESS_OF_LOSS', 'AGGREGATE', 'RETROCESSION'
    ))
);

CREATE TABLE IF NOT EXISTS contract_commutation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    commutation_number VARCHAR(32) NOT NULL,
    commutation_type VARCHAR(20) NOT NULL,
    effective_date DATE NOT NULL,
    valuation_date DATE NOT NULL,
    agreed_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    basis VARCHAR(16) NOT NULL,
    status VARCHAR(12) NOT NULL,
    release_document_id UUID,
    release_date DATE,
    payment_id UUID,
    savings_amount NUMERIC(19,4),
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    executed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_contract_commutation_number UNIQUE (tenant_id, contract_id, commutation_number),
    CONSTRAINT ck_contract_commutation_amount CHECK (agreed_amount >= 0),
    CONSTRAINT ck_contract_commutation_savings CHECK (savings_amount IS NULL OR savings_amount >= 0),
    CONSTRAINT ck_contract_commutation_dates CHECK (valuation_date <= effective_date),
    CONSTRAINT ck_contract_commutation_status CHECK (status IN (
        'PROPOSED', 'APPROVED', 'EXECUTED', 'REJECTED', 'REVERSED'
    )),
    CONSTRAINT ck_contract_commutation_type CHECK (commutation_type IN (
        'FULL', 'PARTIAL', 'LOSS_PORTFOLIO', 'BUYOUT', 'NOVATION'
    )),
    CONSTRAINT ck_contract_commutation_basis CHECK (basis IN (
        'NEGOTIATED', 'CONTRACTUAL', 'INDUCED', 'REGULATORY', 'INSOLVENCY'
    ))
);

CREATE TABLE IF NOT EXISTS contract_technical_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    account_number VARCHAR(32) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    ceded_premium NUMERIC(19,4) NOT NULL,
    ceded_losses NUMERIC(19,4) NOT NULL,
    commission NUMERIC(19,4) NOT NULL,
    other_charges NUMERIC(19,4) NOT NULL,
    balance NUMERIC(19,4) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_contract_technical_account_period
        UNIQUE (tenant_id, contract_id, period_start, period_end),
    CONSTRAINT ck_contract_technical_account_period CHECK (period_start < period_end),
    CONSTRAINT ck_contract_technical_account_amounts CHECK (
        ceded_premium >= 0 AND ceded_losses >= 0 AND commission >= 0 AND other_charges >= 0),
    CONSTRAINT ck_contract_technical_account_status CHECK (status IN ('DRAFT', 'ISSUED', 'SETTLED')),
    CONSTRAINT fk_contract_technical_account_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_technical_account_contract
    ON contract_technical_account (tenant_id, contract_id, period_start);

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
