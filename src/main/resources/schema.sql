-- Contract expert schema.
--
-- Idempotent DDL: the file is re-applied on every service start
-- (`spring.sql.init.mode=always`), so every statement must be safe to repeat.
-- Column names follow SPEC 11.3.4; the exceptions are documented in
-- IMPLEMENTATION.md (notably `contract_term` and the cession business references,
-- which the ASSUMED book wall requires instead of Policy/Direct Claims ids).

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

-- Columns added by the second slice. ALTER TABLE ... IF NOT EXISTS keeps the
-- statement idempotent on a database created by the first slice.
ALTER TABLE contract ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS bound_by UUID;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS commuted_at TIMESTAMPTZ;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_contract_tenant_status ON contract (tenant_id, status);

-- Business-key sequence (`{PREFIX}-{YEAR}-{SEQ}`, SPEC 10.1) for generated
-- cession, bordereau, and commutation numbers.
CREATE TABLE IF NOT EXISTS contract_business_key (
    tenant_id UUID NOT NULL,
    prefix VARCHAR(8) NOT NULL,
    year INTEGER NOT NULL,
    next_value INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, prefix, year)
);

-- SPEC 11.3.4 `contract_layer`.
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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_layer_number UNIQUE (tenant_id, contract_id, layer_number),
    CONSTRAINT ck_contract_layer_attachment CHECK (attachment_amount >= 0),
    CONSTRAINT ck_contract_layer_limit CHECK (limit_amount > 0),
    CONSTRAINT ck_contract_layer_aggregate CHECK (
        aggregate_limit_amount IS NULL OR aggregate_limit_amount >= limit_amount),
    CONSTRAINT ck_contract_layer_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_layer_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_layer_status CHECK (status IN (
        'QUOTED', 'BOUND', 'IN_FORCE', 'EXHAUSTED', 'EXPIRED', 'CANCELLED'
    )),
    CONSTRAINT fk_contract_layer_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_layer_contract ON contract_layer (tenant_id, contract_id, layer_number);

-- SPEC 11.3.4 `contract_participant`.
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
    signed_at TIMESTAMPTZ,
    authorization_ref VARCHAR(64),
    effective_from DATE,
    effective_to DATE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
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
    )),
    CONSTRAINT fk_contract_participant_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE,
    CONSTRAINT fk_contract_participant_layer FOREIGN KEY (layer_id)
        REFERENCES contract_layer (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_participant_contract
    ON contract_participant (tenant_id, contract_id, status);
-- SPEC 11.3.4 unique active participation; COALESCE maps contract-level rows to
-- the zero UUID so the uniqueness rule is a single index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_participant_active
    ON contract_participant (
        tenant_id, contract_id,
        COALESCE(layer_id, '00000000-0000-0000-0000-000000000000'::uuid),
        party_id, participant_role)
    WHERE status = 'ACTIVE';

-- Contract terms. SPEC 11.3.4 splits financial terms (`contract_terms`) from the
-- clause library (`contract_wording`); this slice implements the key/value term
-- registry that the bind rule "required terms by contract type" needs, and links
-- each term to the clause it came from (`source_clause`). See IMPLEMENTATION.md.
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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_term_code UNIQUE (tenant_id, contract_id, term_code),
    CONSTRAINT ck_contract_term_value_type CHECK (value_type IN ('MONEY', 'PERCENT', 'TEXT', 'DATE', 'INTEGER', 'BOOLEAN')),
    CONSTRAINT ck_contract_term_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT fk_contract_term_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_term_contract ON contract_term (tenant_id, contract_id);

-- SPEC 11.3.4 `contract_security`.
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
    effective_from DATE,
    effective_to DATE,
    expiry_date DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    collateral_quality VARCHAR(24),
    document_id UUID,
    release_conditions VARCHAR(400),
    approval_ref VARCHAR(64),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_contract_security_amounts CHECK (required_amount >= 0 AND held_amount >= 0),
    CONSTRAINT ck_contract_security_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_security_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_security_expiry CHECK (expiry_date IS NULL OR expiry_date >= effective_from),
    CONSTRAINT ck_contract_security_status CHECK (status IN (
        'ACTIVE', 'REQUIRED', 'OUTSTANDING', 'PARTIAL', 'RELEASED', 'EXPIRED', 'BREACH'
    )),
    CONSTRAINT ck_contract_security_type CHECK (security_type IN (
        'LETTER_OF_CREDIT', 'TRUST_FUND', 'FUNDS_WITHHELD', 'CASH_ADVANCE',
        'PARENT_GUARANTEE', 'BANK_GUARANTEE', 'SECURITIES'
    )),
    CONSTRAINT fk_contract_security_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

-- The public security API records the simplified collateral shape: no
-- effective-from is supplied, and its status starts ACTIVE (or EXPIRED when the
-- expiry date is past) instead of the bind slice's REQUIRED/OUTSTANDING.
-- The ALTERs are safe to repeat on an existing database.
ALTER TABLE contract_security ALTER COLUMN effective_from DROP NOT NULL;
ALTER TABLE contract_security ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE contract_security ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE contract_security ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE contract_security DROP CONSTRAINT IF EXISTS ck_contract_security_status;
ALTER TABLE contract_security ADD CONSTRAINT ck_contract_security_status CHECK (status IN (
    'ACTIVE', 'REQUIRED', 'OUTSTANDING', 'PARTIAL', 'RELEASED', 'EXPIRED', 'BREACH'
));

CREATE INDEX IF NOT EXISTS ix_contract_security_contract ON contract_security (tenant_id, contract_id, status);
-- Exact tenant/contract lookup index for the public newest-first list.
CREATE INDEX IF NOT EXISTS ix_contract_security_tenant_contract
    ON contract_security (tenant_id, contract_id);

-- SPEC 11.3.4 `contract_cession`. Contract is ASSUMED book only: the check
-- constraint is the database-level book wall.
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
    recorded_at TIMESTAMPTZ NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_cession_number UNIQUE (tenant_id, contract_id, cession_number),
    CONSTRAINT ck_contract_cession_book CHECK (source_vertical = 'ASSUMED'),
    CONSTRAINT ck_contract_cession_source CHECK (source_type IN ('POLICY', 'CLAIM', 'LOSS_ADVICE')),
    CONSTRAINT ck_contract_cession_pct CHECK (cession_pct IS NULL OR (cession_pct >= 0 AND cession_pct <= 100)),
    CONSTRAINT ck_contract_cession_amounts CHECK (
        (ceded_premium_amount IS NULL OR ceded_premium_amount >= 0)
        AND (ceded_loss_amount IS NULL OR ceded_loss_amount >= 0)
        AND (ceded_premium_amount IS NOT NULL OR ceded_loss_amount IS NOT NULL)),
    CONSTRAINT ck_contract_cession_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_cession_dates CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT ck_contract_cession_status CHECK (status IN (
        'RECORDED', 'CONFIRMED', 'REVERSED', 'DISPUTED'
    )),
    CONSTRAINT ck_contract_cession_type CHECK (cession_type IN (
        'QUOTA_SHARE', 'SURPLUS', 'FACULTATIVE', 'EXCESS_OF_LOSS', 'AGGREGATE', 'RETROCESSION'
    )),
    CONSTRAINT fk_contract_cession_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE,
    CONSTRAINT fk_contract_cession_layer FOREIGN KEY (layer_id)
        REFERENCES contract_layer (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS ix_contract_cession_contract
    ON contract_cession (tenant_id, contract_id, status);

-- SPEC 11.3.4 `contract_bordereau`, extended with the period-bordereau
-- lifecycle. A period bordereau sets `period_start`/`period_end`,
-- `received_date`, the four declared amounts, and (after processing) the
-- technical account it posted to. The legacy ingest header leaves those null
-- and owns the remaining columns, so both shapes share one table. The
-- `period_start IS NOT NULL` predicate is the discriminator.
CREATE TABLE IF NOT EXISTS contract_bordereau (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    bordereau_number VARCHAR(32) NOT NULL,
    -- Legacy ingest header (nullable for period bordereaux).
    bordereau_type VARCHAR(24),
    period_from DATE,
    period_to DATE,
    received_at TIMESTAMPTZ,
    source_document_id UUID,
    source_format VARCHAR(12),
    source_reference VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    line_count INTEGER NOT NULL DEFAULT 0,
    valid_line_count INTEGER,
    exception_count INTEGER,
    total_amount NUMERIC(19,4),
    currency CHAR(3),
    checksum VARCHAR(64),
    processed_at TIMESTAMPTZ,
    notes TEXT,
    -- Period-bordereau lifecycle.
    period_start DATE,
    period_end DATE,
    received_date DATE,
    premium_amount NUMERIC(19,4),
    loss_amount NUMERIC(19,4),
    commission_amount NUMERIC(19,4),
    other_charges_amount NUMERIC(19,4),
    technical_account_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_bordereau_number UNIQUE (tenant_id, contract_id, bordereau_number),
    CONSTRAINT ck_contract_bordereau_period CHECK (
        period_from IS NULL OR period_to IS NULL OR period_from <= period_to),
    CONSTRAINT ck_contract_bordereau_line_count CHECK (line_count >= 0),
    CONSTRAINT ck_contract_bordereau_valid_count CHECK (
        valid_line_count IS NULL OR (valid_line_count >= 0 AND valid_line_count <= line_count)),
    CONSTRAINT ck_contract_bordereau_exception_count CHECK (
        exception_count IS NULL OR exception_count >= 0),
    CONSTRAINT ck_contract_bordereau_amount CHECK (total_amount IS NULL OR total_amount >= 0),
    CONSTRAINT ck_contract_bordereau_currency CHECK (
        currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_bordereau_status CHECK (status IN (
        'RECEIVED', 'PARSING', 'VALIDATED', 'PARTIALLY_POSTED', 'POSTED', 'REJECTED',
        'SUPERSEDED', 'PROCESSED', 'FAILED'
    )),
    CONSTRAINT ck_contract_bordereau_format CHECK (
        source_format IS NULL OR source_format IN ('CSV', 'XLSX', 'XML', 'JSON', 'MANUAL')),
    CONSTRAINT ck_contract_bordereau_type CHECK (
        bordereau_type IS NULL OR bordereau_type IN (
            'PREMIUM', 'CLAIMS', 'CESSION', 'ACCOUNTING', 'LOSS_DEVELOPMENT', 'SECURITY',
            'CASH_LOSS'
        )),
    CONSTRAINT ck_contract_bordereau_financial_period CHECK (
        period_start IS NULL OR period_end IS NULL OR period_start < period_end),
    CONSTRAINT ck_contract_bordereau_financial_amounts CHECK (
        (premium_amount IS NULL OR premium_amount >= 0)
        AND (loss_amount IS NULL OR loss_amount >= 0)
        AND (commission_amount IS NULL OR commission_amount >= 0)
        AND (other_charges_amount IS NULL OR other_charges_amount >= 0)),
    CONSTRAINT fk_contract_bordereau_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

-- The CREATE above only runs on a database that does not have the table yet.
-- These statements bring a database created by the ingest slice forward without
-- dropping its rows: the legacy columns become nullable, the note becomes text,
-- the status constraint learns the period lifecycle, and the period keys and
-- checks are added. Each statement is safe to re-run on every start.
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS period_start DATE;
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS period_end DATE;
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS received_date DATE;
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS premium_amount NUMERIC(19,4);
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS loss_amount NUMERIC(19,4);
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS commission_amount NUMERIC(19,4);
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS other_charges_amount NUMERIC(19,4);
ALTER TABLE contract_bordereau ADD COLUMN IF NOT EXISTS technical_account_id UUID;
ALTER TABLE contract_bordereau ALTER COLUMN bordereau_type DROP NOT NULL;
ALTER TABLE contract_bordereau ALTER COLUMN period_from DROP NOT NULL;
ALTER TABLE contract_bordereau ALTER COLUMN period_to DROP NOT NULL;
ALTER TABLE contract_bordereau ALTER COLUMN received_at DROP NOT NULL;
ALTER TABLE contract_bordereau ALTER COLUMN source_format DROP NOT NULL;
ALTER TABLE contract_bordereau ALTER COLUMN notes TYPE TEXT;
ALTER TABLE contract_bordereau DROP CONSTRAINT IF EXISTS ck_contract_bordereau_status;
ALTER TABLE contract_bordereau ADD CONSTRAINT ck_contract_bordereau_status CHECK (status IN (
    'RECEIVED', 'PARSING', 'VALIDATED', 'PARTIALLY_POSTED', 'POSTED', 'REJECTED',
    'SUPERSEDED', 'PROCESSED', 'FAILED'
));
ALTER TABLE contract_bordereau DROP CONSTRAINT IF EXISTS ck_contract_bordereau_financial_period;
ALTER TABLE contract_bordereau ADD CONSTRAINT ck_contract_bordereau_financial_period CHECK (
    period_start IS NULL OR period_end IS NULL OR period_start < period_end);
ALTER TABLE contract_bordereau DROP CONSTRAINT IF EXISTS ck_contract_bordereau_financial_amounts;
ALTER TABLE contract_bordereau ADD CONSTRAINT ck_contract_bordereau_financial_amounts CHECK (
    (premium_amount IS NULL OR premium_amount >= 0)
    AND (loss_amount IS NULL OR loss_amount >= 0)
    AND (commission_amount IS NULL OR commission_amount >= 0)
    AND (other_charges_amount IS NULL OR other_charges_amount >= 0));

CREATE INDEX IF NOT EXISTS ix_contract_bordereau_contract
    ON contract_bordereau (tenant_id, contract_id, period_from);
CREATE INDEX IF NOT EXISTS ix_contract_bordereau_period
    ON contract_bordereau (tenant_id, contract_id, period_start);
-- NULL periods (legacy ingest rows) are not period duplicates, so the unique
-- index is partial; PostgreSQL treats them as outside the financial lifecycle.
CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_bordereau_period
    ON contract_bordereau (tenant_id, contract_id, period_start, period_end)
    WHERE period_start IS NOT NULL;

-- SPEC 11.3.4 `contract_commutation`.
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
    approved_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_commutation_number UNIQUE (tenant_id, contract_id, commutation_number),
    CONSTRAINT ck_contract_commutation_amount CHECK (agreed_amount >= 0),
    CONSTRAINT ck_contract_commutation_savings CHECK (savings_amount IS NULL OR savings_amount >= 0),
    CONSTRAINT ck_contract_commutation_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_commutation_dates CHECK (valuation_date <= effective_date),
    CONSTRAINT ck_contract_commutation_status CHECK (status IN (
        'PROPOSED', 'APPROVED', 'EXECUTED', 'REJECTED', 'REVERSED'
    )),
    CONSTRAINT ck_contract_commutation_type CHECK (commutation_type IN (
        'FULL', 'PARTIAL', 'LOSS_PORTFOLIO', 'BUYOUT', 'NOVATION'
    )),
    CONSTRAINT ck_contract_commutation_basis CHECK (basis IN (
        'NEGOTIATED', 'CONTRACTUAL', 'INDUCED', 'REGULATORY', 'INSOLVENCY'
    )),
    CONSTRAINT fk_contract_commutation_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_commutation_contract
    ON contract_commutation (tenant_id, contract_id, status);

-- A contract has at most one executed commutation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_commutation_executed
    ON contract_commutation (tenant_id, contract_id) WHERE status = 'EXECUTED';

-- SPEC 11.3.4 `technical_account`: the periodic account for one contract
-- period. The brief's table and column names are kept; SPEC's separate
-- `technical_account_line` breakdown is not part of this slice. `balance` is
-- derived at creation time as ceded_premium - commission - ceded_losses
-- + other_charges, so a positive value is due from the cedent to the reinsurer.
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
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_technical_account_period
        UNIQUE (tenant_id, contract_id, period_start, period_end),
    CONSTRAINT ck_contract_technical_account_period CHECK (period_start < period_end),
    CONSTRAINT ck_contract_technical_account_amounts CHECK (
        ceded_premium >= 0 AND ceded_losses >= 0 AND commission >= 0 AND other_charges >= 0),
    CONSTRAINT ck_contract_technical_account_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_technical_account_status CHECK (status IN ('DRAFT', 'ISSUED', 'SETTLED')),
    CONSTRAINT fk_contract_technical_account_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_technical_account_contract
    ON contract_technical_account (tenant_id, contract_id, period_start);

-- Cash calls: a request for a forward payment on a contract period. A cash call
-- is a child aggregate of the contract; its call number is unique per tenant and
-- contract. `amount` is strictly positive because a cash call always requests
-- money, and the status path is REQUESTED -> ISSUED -> SETTLED or
-- REQUESTED -> CANCELLED. The unique key, the period ordering, the positive
-- amount, the currency shape, and the status domain are enforced here as well as
-- by the API.
CREATE TABLE IF NOT EXISTS contract_cash_call (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    call_number VARCHAR(32) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_contract_cash_call_number UNIQUE (tenant_id, contract_id, call_number),
    CONSTRAINT ck_contract_cash_call_period CHECK (period_start < period_end),
    CONSTRAINT ck_contract_cash_call_amount CHECK (amount > 0),
    CONSTRAINT ck_contract_cash_call_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_cash_call_status CHECK (status IN (
        'REQUESTED', 'ISSUED', 'SETTLED', 'CANCELLED'
    )),
    CONSTRAINT fk_contract_cash_call_contract FOREIGN KEY (contract_id)
        REFERENCES contract (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_contract_cash_call_contract
    ON contract_cash_call (tenant_id, contract_id, created_at);

-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
-- The expert appends the event in the same transaction as the aggregate write,
-- and the platform dispatcher drains PENDING rows over authenticated HTTPS.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
