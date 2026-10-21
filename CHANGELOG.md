# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- `contract_layer`, `contract_participant`, `contract_term`, `contract_security`,
  `contract_cession`, `contract_bordereau`, `contract_commutation`,
  `contract_technical_account`, `contract_technical_account_line`, and
  `contract_cash_call` tables with idempotent DDL, check constraints, unique
  keys, and foreign keys.
- Internal APIs for layers (with a tower summary), participants, terms (with the
  required-terms view), security, cessions, bordereaux, commutations (propose,
  approve, execute), technical accounts (create, newest-period-first list,
  DRAFT -> ISSUED -> SETTLED status), bind eligibility, and bind. The technical
  account resource is also exposed at the brief's
  `/api/v1/contracts/{contractId}/technical-accounts` path.
- Cash calls: `contract_cash_call` with a generated `CC-{YEAR}-{SEQ}` number
  unique per tenant and contract. The public
  `GET/POST /api/v1/contracts/{id}/cash-calls` and
  `PATCH .../{cashCallId}/status` endpoints request a cash call
  (`REQUESTED`) and move it through `ISSUED -> SETTLED` or `CANCELLED`; terminal
  statuses are immutable. Period, strictly positive amount, due date, and
  currency are validated, and every transition writes its
  `contract.cash-call.*` event through the transactional outbox.
- Security/collateral: the public
  `GET/POST /api/v1/contracts/{contractId}/security` endpoints record a security
  type, a strictly positive required amount, non-negative held collateral, the
  contract currency, an optional provider party, expiry date, and approval
  reference. The status is derived (`ACTIVE`, or `EXPIRED` when the expiry date
  is past), the list is newest-first with exactly the public collateral fields,
  and the create writes `contract.security.recorded` through the transactional
  outbox. The existing internal security endpoint is unchanged.
- Security lifecycle: `PATCH /api/v1/contracts/{contractId}/security/{securityId}/status`
  releases or expires a live collateral record (`ACTIVE`, `OUTSTANDING`,
  `PARTIAL`, or `REQUIRED` -> `RELEASED` or `EXPIRED`; `EXPIRED` -> `RELEASED`).
  `RELEASED` is terminal (409 `INVALID_STATUS_TRANSITION`), the update is
  tenant/contract scoped and stamps `updated_at`, and every change writes
  `contract.security.status-changed` through the transactional outbox in the
  same transaction.
- Invariants: tower overlap (CTR-02), aggregate limit (CTR-03), shares total 100%
  (CTR-01), required terms by contract type, over-collateralisation approval,
  cession capacity (CTR-04) and source share (CTR-05), security breach block
  (CTR-11), commutation approval and release (CTR-12), contract currency
  (CTR-17), and the ASSUMED book wall.
- Business-key generation (`CES`, `BRD`, `COM`, `TA`, `CC`) and bind/commutation
  timestamps.
- Technical-account line detail: `contract_technical_account_line` stores the
  SPEC 11.3.4 detail vocabulary `PREMIUM`, `COMMISSION`, `BROKERAGE`,
  `PAID_LOSS`, `OUTSTANDING_LOSS`, `IBNR`, `ALAE`, `REINSTATEMENT`, `CASH_CALL`,
  `FUNDS_WITHHELD`, `TAX`, `ADJUSTMENT`, and `OTHER`, with an optional reference
  and description and a strictly positive amount (`numeric(19,4)`). Every type
  rolls into one of the four account header components: `PREMIUM` -> ceded
  premium; `PAID_LOSS`, `OUTSTANDING_LOSS`, `IBNR`, `ALAE`, `REINSTATEMENT`, and
  `CASH_CALL` -> ceded losses; `COMMISSION` and `BROKERAGE` -> commission;
  `FUNDS_WITHHELD`, `TAX`, `ADJUSTMENT`, and `OTHER` -> other charges. The first
  slice's aggregate `LOSS` type is no longer accepted; the PostgreSQL schema
  folds existing `LOSS` rows into `PAID_LOSS`, preserving the loss bucket total.
  The public
  `GET/POST /api/v1/contracts/{contractId}/technical-accounts/{accountId}/lines`
  endpoints list the lines with a detailed per-type `totals` map and a separate
  `bucketTotals` map keyed `premium`, `loss`, `commission`, and `other`, and add
  a line (201); `DELETE .../{lineId}` removes one (204). Both mutations are
  DRAFT-only (409 `ACCOUNT_NOT_EDITABLE`) and write
  `contract.technical-account.line-added`/`.line-removed` through the
  transactional outbox. Issuing an account that has lines reconciles each
  bucket total to its header component and rejects a mismatch with 409
  `ACCOUNT_LINES_DO_NOT_RECONCILE` and one detail per offending bucket naming
  the contributing line types; an account with no lines keeps the legacy
  header-only issue path.
- Technical-account balance convention
  (`cededPremium - commission - cededLosses + otherCharges`): positive is due
  from the cedent to the reinsurer. Periods are unique per tenant and contract;
  amounts must be non-negative and the currency three letters.
- Period bordereaux: `contract_bordereau` now stores `period_start`,
  `period_end`, `received_date`, `premium_amount`, `loss_amount`,
  `commission_amount`, `other_charges_amount`, and `technical_account_id`, with
  a unique period per tenant and contract. The public
  `GET/POST /api/v1/contracts/{id}/bordereaux` and
  `PATCH .../{bordereauId}/status` endpoints receive a bordereau, process it into
  a new or existing technical account (`RECEIVED -> PROCESSED`), or record a
  failure (`RECEIVED -> FAILED`). Terminal statuses are immutable.
  `contract.bordereau.received`/`.processed`/`.failed` are written through the
  transactional outbox in the same transaction as the row and account.
- The legacy ingest-header bordereau slice is retained under
  `/internal/v1/contracts/{id}/bordereaux` and shares the table, discriminated by
  `period_start`.
- Bind is a command with an approver; `PATCH /status` no longer accepts `BOUND`.
- Transactional outbox production through `com.eia.platform.libs.outbox`:
  `outbox_event` DDL, `contract.contract.created`, `.updated`, `.bound`,
  `.signed`, `.endorsed`, `.expired`, `.cancelled`, `.commuted`,
  `contract.cession.recorded`, `contract.technical-account.created`/`.issued`/
  `.settled`/`.line-added`/`.line-removed`,
  `contract.bordereau.received`/`.processed`/`.failed`, and
  `contract.cash-call.requested`/`.issued`/`.settled`/`.cancelled`, written in
  the aggregate transaction.
- Quartz outbox dispatch job, enabled only when `eia.outbox.enabled=true`, plus
  the `eia.outbox` configuration block.
- 292 JUnit tests, including outbox payload, rollback, failure-atomicity,
  security lifecycle, technical-account line reconciliation, bordereau and
  cash-call lifecycle, and scheduling tests.

### Changed

- The contract projection adds `boundAt`, `boundBy`, `commutedAt`, and `closedAt`.
- Repository baseline.
