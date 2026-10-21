# Implementation state

The expert persists the Contract aggregate and is the final guard for every
invariant that needs stored state. This file records what the second slice
implements, the decisions taken where SPEC 11.3 and the task brief differ, and
what remains.

## Implemented

| Area | Table / endpoint | Invariant |
|---|---|---|
| Contract header | `contract` | SPEC 11.3.4 header; lifecycle in `ContractStatus` |
| Layers | `contract_layer`, `GET/POST /internal/v1/contracts/{id}/layers`, `GET .../layers/summary` | CTR-02 tower has no overlap; CTR-03 aggregate limit >= layer limit; layer dates inside the contract term; layer currency equals contract currency; `note` records an intentional gap |
| Participants | `contract_participant`, `GET/POST .../participants` | CTR-01 shares total 100% (+/-0.0001) before bind; a share may not push a layer above 100%; signed line <= share; LEAD requires `leadFlag` |
| Terms | `contract_term`, `GET/POST .../terms`, `GET .../terms/requirements` | required terms by contract type before bind; value type derived from the code; clause code from SPEC 11.3.5 |
| Security | `contract_security`, `GET/POST /api/v1/contracts/{id}/security` (public), `PATCH .../{securityId}/status`, and `GET/POST .../security` (internal) | security type, strictly positive required amount, non-negative held amount, three-letter contract currency; create status derived as ACTIVE or EXPIRED (past expiry); status action ACTIVE/OUTSTANDING/PARTIAL/REQUIRED -> RELEASED or EXPIRED, EXPIRED -> RELEASED, RELEASED terminal (409); `updated_at` stamped; approval reference <= 64 characters; collateral above the secured obligation requires an approval reference and timestamp on the internal slice; CTR-11 breach blocks cessions |
| Cessions | `contract_cession`, `GET/POST .../cessions` | CTR-04 ceded loss within the live tower (contract or named layer); CTR-05 active cessions per source <= 100%; ASSUMED book wall; CTR-17 contract currency; cession inside contract term |
| Bordereaux | `contract_bordereau`, `GET/POST .../bordereaux`, `GET .../bordereaux/{bordereauId}` | coherent period, line counts, contract currency for the declared total, unique bordereau number |
| Period bordereaux | `contract_bordereau` (`period_start` set), `GET/POST /api/v1/contracts/{id}/bordereaux`, `PATCH .../{bordereauId}/status` | period start before end; non-negative declared premium, loss, commission, and other charges; non-negative line count; three-letter currency; unique period per tenant and contract; `RECEIVED -> PROCESSED` creates or reuses the matching technical account and links it; `RECEIVED -> FAILED` records the failure; terminal statuses are immutable; tenant/contract wall |
| Commutation | `contract_commutation`, `GET/POST .../commutations`, `POST .../{id}/approve`, `POST .../{id}/execute` | COM-01 approval before execution; CTR-12 release document required; FULL commutation walks COMMUTED -> CLOSED and releases security; one executed commutation per contract |
| Technical accounts | `contract_technical_account`, `contract_technical_account_line`, `GET/POST /api/v1/contracts/{id}/technical-accounts`, `PATCH .../{accountId}/status`, `GET/POST .../{accountId}/lines`, `DELETE .../{accountId}/lines/{lineId}` | unique period per tenant and contract; `periodStart < periodEnd`; non-negative amounts; three-letter currency; derived balance; lines editable only while DRAFT; issuing with any lines requires each bucket total to equal its header component (CTR-08); DRAFT -> ISSUED -> SETTLED; tenant/contract wall |
| Cash calls | `contract_cash_call`, `GET/POST /api/v1/contracts/{id}/cash-calls`, `PATCH .../{cashCallId}/status` | period start before end; amount strictly positive; due date required; three-letter currency; unique call number per tenant and contract; REQUESTED -> ISSUED -> SETTLED or REQUESTED -> CANCELLED; terminal statuses immutable; tenant/contract wall |
| Bind | `GET .../bind-eligibility`, `POST .../bind` | every precondition is evaluated and reported; bind requires an approver (CTR-15) and is written atomically with the checks under `SELECT ... FOR UPDATE` |
| Transactional outbox | `outbox_event` | the contract/cession write and its canonical event are committed or rolled back together (SPEC 7.5.1) |

`PATCH /internal/v1/contracts/{id}/status` no longer accepts `BOUND`. Bind is a
command (`POST .../bind`) because it needs the tower, share, term, security, and
approval checks; the same rule is enforced in the domain service.

## Transactional outbox

The expert writes domain events through `com.eia.platform.libs.outbox` in the
same transaction as the aggregate write:

- `POST /internal/v1/contracts` -> `contract.contract.created`
- `PATCH /internal/v1/contracts/{id}/status` -> `contract.contract.updated` plus
  the catalog lifecycle event for the target status when one exists
  (`contract.contract.signed`, `.endorsed`, `.expired`, `.cancelled`,
  `.commuted`)
- `POST /internal/v1/contracts/{id}/bind` -> `contract.contract.updated` and
  `contract.contract.bound`
- `POST /internal/v1/contracts/{id}/cessions` -> `contract.cession.recorded`
  (the catalog name for the brief's `contract.contract.cession-added`)
- `POST /internal/v1/contracts/{id}/commutations/{commutationId}/execute` ->
  `contract.contract.updated` and `contract.contract.commuted`
- `POST /api/v1/contracts/{contractId}/technical-accounts` (also under
  `/internal/v1`) -> `contract.technical-account.created`
- `PATCH .../technical-accounts/{accountId}/status` -> the matching
  `contract.technical-account.issued` or `.settled` event
- `POST .../technical-accounts/{accountId}/lines` ->
  `contract.technical-account.line-added`
- `DELETE .../technical-accounts/{accountId}/lines/{lineId}` ->
  `contract.technical-account.line-removed`
- `POST /api/v1/contracts/{contractId}/bordereaux` ->
  `contract.bordereau.received`
- `PATCH .../bordereaux/{bordereauId}/status` -> `contract.bordereau.processed`
  (with the linked technical account) or `contract.bordereau.failed`
- `POST /api/v1/contracts/{contractId}/cash-calls` (also under
  `/internal/v1`) -> `contract.cash-call.requested`
- `PATCH .../cash-calls/{cashCallId}/status` -> the matching
  `contract.cash-call.issued`, `.settled`, or `.cancelled` event
- `POST /api/v1/contracts/{contractId}/security` (and the internal
  `/internal/v1/contracts/{id}/security` endpoint) ->
  `contract.security.recorded`
- `PATCH /api/v1/contracts/{contractId}/security/{securityId}/status` ->
  `contract.security.status-changed`

Contract events use `aggregateType=Contract`, `vertical=contract`,
`book=ASSUMED`, and the outbox partition key is the contract id. The cession is
a child aggregate, so `contract.cession.recorded` uses `aggregateType=Cession`
and the cession id/version while keeping the contract id as the partition key.
A technical account is also a child aggregate: `aggregateType=TechnicalAccount`,
its own id, and the lifecycle version 1 (DRAFT), 2 (ISSUED), or 3 (SETTLED),
still partitioned by the contract id. A technical-account line is a grandchild
fact: `aggregateType=TechnicalAccountLine`, its own id, and version 1 for both
the added and removed facts (the event type is part of the outbox key), still
partitioned by the contract id. A cash call is a child aggregate too:
`aggregateType=CashCall`, its own id, and the lifecycle version 1 (REQUESTED),
2 (ISSUED or CANCELLED), or 3 (SETTLED), still partitioned by the contract id.
A security record is a child aggregate with `aggregateType=Security`, its own id,
version 1 for the recorded fact, and the contract id as the partition key. The
first status change is version 2 and a release from `EXPIRED` is version 3, so
the two lifecycle facts of one record keep distinct outbox keys.
Rows start at version 0 for API compatibility, so contract event
`aggregateVersion` is the row version plus one.
Event ids are deterministic (tenant + aggregate + version + type), making a
retry idempotent.

Payloads carry `contractId`, `contractNumber`, `contractType`, `status`,
`version`, `cedentPartyId`, `reinsurerPartyId`, `currency`, `effectiveDate`,
and `expirationDate`. The bind payload adds `boundAt`, `boundBy`, and
`limitAmount`; the cession payload adds the cession identifiers, `cessionPct`,
`cededPremiumAmount`, `cededLossAmount`, `contractLimitAmount`, and the source
references; the commuted payload adds the commutation identifier, type, basis,
`agreedAmount`, `savingsAmount`, dates, release document, `contractClosed`, and
`releasedSecurities`. Technical-account payloads carry `contractId`,
`contractNumber`, `accountId`, `accountNumber`, `periodStart`, `periodEnd`,
`currency`, `balance`, and `status`. Technical-account-line payloads carry
`contractId`, `accountId`, `lineId`, `lineType`, and `amount`. Period-bordereau
payloads carry
`contractId`, `contractNumber`, `bordereauId`, `bordereauNumber`, `period` (a
`start`/`end` object), `periodStart`, `periodEnd`, `currency`, `status`, the four
declared amounts, `lineCount`, and — once linked — `technicalAccountId`. Cash-call
payloads carry `contractId`, `contractNumber`, `cashCallId`, `callNumber`,
`period` (a `start`/`end` object), `periodStart`, `periodEnd`, `amount`,
`currency`, `dueDate`, and `status`. Security payloads carry `contractId`,
`securityId`, `securityType`, `requiredAmount`, `heldAmount`, `currency`, and
`status`; the status-changed payload carries `contractId`, `securityId`,
`securityType`, `previousStatus`, and `status`. Money and percentages are decimal
strings (SPEC 10.3).

Event names are not all in the canonical catalog. The catalog has no generic
`contract.contract.updated`, so the expert publishes that requested change fact
as an additive name (EV-05 treats an added event as compatible) and also
publishes the specific lifecycle fact. The brief's
`contract.contract.cession-added` is published under the catalog's actual name,
`contract.cession.recorded`. The technical-account events keep the brief's
`contract.technical-account.*` names (create, issue, settle, plus the line
added/removed facts); the catalog's only account event is the narrower
`contract.account.issued`, so the five names are additive child facts rather
than replacements for it. The period-bordereau events keep the brief's
`contract.bordereau.received`/`.processed`/`.failed` names; the catalog names the
last two facts `.posted` and `.rejected`, so the brief's names are additive. Cash
calls keep the brief's `contract.cash-call.requested`/`.issued`/`.settled`/
`.cancelled` child facts; the catalog has no cash-call event, so the four names
are additive. The collateral events keep the brief's
`contract.security.recorded` and `contract.security.status-changed` names; the
catalog has no security event, so both are additive. The legacy ingest header
publishes no event.

The `outbox_event` DDL is repeated in `schema.sql` using the canonical
PostgreSQL definition from
`eia-platform/libs/src/main/resources/db/outbox_event.sql`. Dispatch is
conditional: `spring.quartz.auto-startup` and the
`ContractOutboxSchedulingConfiguration` job are enabled only when
`eia.outbox.enabled=true` (or `EIA_OUTBOX_ENABLED=true`). The outbox row is
written even when dispatch is disabled, so no event is lost.

## Decisions and deviations

- **`contract_term` rather than `contract_terms` + `contract_wording`.** The task
  brief asks for a term table with `term_code`, `value`, effective dates, and a
  source clause. SPEC 11.3.4 models financial terms as one wide `contract_terms`
  row plus a separate `contract_wording` clause library. The key/value registry
  implemented here covers the financial-term facts as term codes
  (`CEDING_COMMISSION_PCT`, `BROKERAGE_PCT`, `FUNDS_WITHHELD_PCT`, ...) and links
  each to a SPEC 11.3.5 clause code. The wide `contract_terms`,
  `contract_sliding_scale`, `contract_premium`, `contract_reinstatement`,
  `contract_wording`, `contract_section`, and
  `contract_mass_tort_profile` are still gaps.
- **Cession business references.** `contract_cession` stores `policy_ref` and
  `claim_ref` strings rather than SPEC `source_id` UUIDs, because Contract is an
  ASSUMED-book-only vertical with a hard wall against Policy and Direct Claims.
  `source_vertical` is constrained to `ASSUMED` in the database.
- **`contract_technical_account` with a detailed line table.** The brief names the
  table and a compact set of header columns, so the slice implements
  `account_number`, the period, currency, ceded premium, ceded losses,
  commission, other charges, and a derived balance. The SPEC 11.3.4 detail
  vocabulary is kept in `contract_technical_account_line`:
  `PREMIUM`, `COMMISSION`, `BROKERAGE`, `PAID_LOSS`, `OUTSTANDING_LOSS`, `IBNR`,
  `ALAE`, `REINSTATEMENT`, `CASH_CALL`, `FUNDS_WITHHELD`, `TAX`, `ADJUSTMENT`,
  and `OTHER`. Every type rolls into one of four reconciliation buckets:
  `PREMIUM` -> `premium`; `PAID_LOSS`, `OUTSTANDING_LOSS`, `IBNR`, `ALAE`,
  `REINSTATEMENT`, and `CASH_CALL` -> `loss`; `COMMISSION` and `BROKERAGE` ->
  `commission`; `FUNDS_WITHHELD`, `TAX`, `ADJUSTMENT`, and `OTHER` -> `other`.
  The `GET .../lines` read model keeps the detailed `totals` map keyed by line
  type and adds `bucketTotals` keyed `premium`, `loss`, `commission`, and
  `other`, so a client can read either view without double counting. Line CRUD
  is DRAFT-only, and DRAFT -> ISSUED reconciles each bucket total to its header
  component when any line exists (CTR-08), reporting all mismatches in one 409
  `ACCOUNT_LINES_DO_NOT_RECONCILE` whose details name the contributing types. An
  account with no lines remains issuable for compatibility with header-only
  accounts. The first slice's aggregate `LOSS` type is no longer accepted; the
  schema migration folds existing `LOSS` rows into `PAID_LOSS`, preserving the
  loss bucket total. The balance sign convention is
  `cededPremium - commission - cededLosses + otherCharges`: positive is due from
  the cedent to the reinsurer. Only the exact `(tenant, contract, period)` key is
  unique; SPEC CTR-07's fall-within-contract and non-overlap rules and the
  `AGREED`/`DISPUTED`/`SUPERSEDED` states remain gaps.
- **Technical-account API prefix and tenant.** The task brief addresses the
  resource at `/api/v1/contracts/{contractId}/technical-accounts`; the expert's
  other child slices are internal, so the controller maps both that path and
  `/internal/v1/...`. The tenant is read from `X-EIA-Tenant` (public convention)
  or `tenantId` (internal convention); supplying both with different values is a
  400. Account numbers use the shared `TA` business key (`TA-2026-00001`).
- **Period bordereaux share `contract_bordereau` with the ingest header.** The
  first slice already used the SPEC table for a document-ingest header; the
  period lifecycle adds `period_start`, `period_end`, `received_date`, the four
  declared amounts, and `technical_account_id`. Defining a second table would
  duplicate the SPEC name, so one table carries both shapes. `period_start IS
  NOT NULL` is the discriminator: the period queries always filter on it, the
  ingest list filters on `IS NULL`, and the PostgreSQL period key is a partial
  unique index so ingest rows (null period) never collide. The legacy columns
  become nullable through guarded `ALTER TABLE` statements, so an existing
  ingest database is upgraded in place and the DDL remains re-runnable.
  `notes` is `text`; the legacy slice still caps caller input at 400 characters
  and the period slice at 4000.
- **Period-bordereau API and events.** The period resource is mapped only at the
  brief's `/api/v1/contracts/{contractId}/bordereaux` because the internal path
  is already occupied by the ingest header. The tenant comes from `X-EIA-Tenant`
  or `tenantId`, as for technical accounts. The lifecycle is deliberately small:
  `RECEIVED -> PROCESSED | FAILED`, with both terminal states immutable (409
  `INVALID_STATUS_TRANSITION`). Processing looks up the technical account for the
  exact contract period and reuses it; otherwise it creates the DRAFT account
  through the technical-account slice. Bordereau and account writes therefore
  share one transaction and one contract row lock. Event names follow the task
  brief (`received`/`processed`/`failed`); the canonical catalog calls the last
  two `posted`/`rejected`, recorded here as an additive mismatch.
- **Cash-call API and number.** The cash call is a compact child aggregate: the
  brief's columns plus a generated `CC-{YEAR}-{SEQ}` call number, unique per
  tenant and contract, and a strictly positive amount. The public path is the
  brief's `/api/v1/contracts/{contractId}/cash-calls`; the internal prefix is
  mapped to the same controller for parity with technical accounts. The tenant
  comes from `X-EIA-Tenant` or `tenantId`, as for the other child slices. A cash
  call may be cancelled only while it is still `REQUESTED`; once issued it can
  only settle, and both terminal statuses are immutable (409
  `INVALID_STATUS_TRANSITION`). The due date is required but is not constrained
  against the period, because a call may be payable after the period closes.
- **Security status action.** The public lifecycle action is the brief's
  `PATCH .../security/{securityId}/status` rather than SPEC 11.3.10's
  `POST .../security/{securityId}/release`; it accepts only `RELEASED` and
  `EXPIRED`, keeps the tenant/contract wall as 404, and stamps `updated_at`. A
  live record can be released or expired and an expired record can still be
  released; `RELEASED` is terminal (409 `INVALID_STATUS_TRANSITION`). The change
  writes `contract.security.status-changed`, an additive child fact because the
  catalog has no security event. The child aggregate uses version 2 for the first
  status change and version 3 for a release from `EXPIRED`, matching the recorded
  fact's version 1 while keeping the outbox aggregate-version key unique.
- **`share_pct` is points of 100** (SPEC 11.3.4 says "Of 100"), not the decimal
  fraction of SPEC 12.2 `Percent`. The wire value is a decimal string with six
  decimals.
- **Foreign keys** are declared inline in `CREATE TABLE IF NOT EXISTS`. The Spring
  SQL initializer splits on semicolons and cannot parse a `DO $$ ... $$` block, so
  the guarded `ALTER TABLE ... ADD CONSTRAINT` form was replaced. On a database
  that already has the child tables, the FKs are not retrofitted; every query is
  tenant- and contract-scoped, so integrity does not depend on them.
- **Cross-field validation is deliberately duplicated**: the domain service
  validates command shape and enumerations, and the expert re-validates and owns
  the aggregate invariants. The expert never trusts the caller.
- **Money and percentages** are `numeric(19,4)` / `numeric(9,6)` in PostgreSQL and
  decimal strings in JSON (SPEC 10.3).

## Tests

- `ContractControllerTest`, `LayerControllerTest`, `LayerRulesTest`,
  `ParticipantControllerTest`, `ParticipantRulesTest`, `TermControllerTest`,
  `RequiredTermsTest`, `SecurityControllerTest`,
  `ContractSecurityControllerTest`, `ContractSecurityRulesTest`,
  `CessionControllerTest`,
  `BordereauHeaderControllerTest`, `BordereauControllerTest`,
  `CommutationControllerTest`, `BindControllerTest`,
  `TechnicalAccountControllerTest`, `TechnicalAccountLineControllerTest`,
  `ContractStatusTest`,
  `CashCallControllerTest`, `OutboxSchedulingEnabledTest`,
  `OutboxSchedulingDisabledTest`, `ContractOutboxFailureRollbackTest`,
  `ContractOutboxIntegrationTest`, `TechnicalAccountOutboxIntegrationTest`,
  `TechnicalAccountLineOutboxIntegrationTest`,
  `BordereauOutboxIntegrationTest`, `CashCallOutboxIntegrationTest`,
  `ContractSecurityOutboxIntegrationTest`,
  `ContractSecurityOutboxFailureRollbackTest` — 292 tests, run with
  `./gradlew test`.
- `ContractOutboxIntegrationTest` proves the event payloads for create, bind,
  cession, and commutation, the catalog lifecycle event for a status change, and
  the rollback rule for every write (neither the row nor the event survives).
  `TechnicalAccountOutboxIntegrationTest` proves creation, newest-period-first
  listing, period uniqueness, DRAFT -> ISSUED -> SETTLED with the matching
  events, invalid transitions, the tenant/contract wall, and row/event/key
  rollback. `TechnicalAccountLineOutboxIntegrationTest` proves line add/list/
  remove and per-type totals, the line-added and line-removed events, DRAFT-only
  mutation, issue reconciliation success, mismatch and missing-component
  rejection with per-type detail, header-only issue, validation, the
  tenant/contract wall, and add/remove rollback.
  `BordereauOutboxIntegrationTest` proves period-bordereau creation,
  newest-period-first listing, duplicate-period rejection, processing into a new
  or existing technical account, failure, invalid transitions, the
  tenant/contract wall, and create/process rollback with their events.
  `CashCallOutboxIntegrationTest` proves creation, newest-first listing, unique
  call numbers per contract, REQUESTED -> ISSUED -> SETTLED and
  REQUESTED -> CANCELLED with their events, invalid and terminal transitions, the
  tenant/contract wall, and create/status rollback.
  `ContractSecurityOutboxIntegrationTest` proves creation with the derived
  ACTIVE/EXPIRED status, newest-first listing with the public fields, validation,
  the tenant/contract wall, the `contract.security.recorded` event, and create
  rollback. It also proves the status lifecycle: ACTIVE -> RELEASED with
  `updated_at` and the `contract.security.status-changed` event, EXPIRED ->
  RELEASED at aggregate version 3, RELEASED terminal, EXPIRED not expirable
  again, the status tenant/contract wall, and status rollback.
  `ContractSecurityOutboxFailureRollbackTest` proves that an outbox append
  failure rolls both the security INSERT and a status UPDATE back.
  `ContractOutboxFailureRollbackTest` proves that an outbox append
  failure rolls the contract INSERT back.
  `OutboxSchedulingEnabledTest`/`DisabledTest` prove the Quartz job is created
  only when `eia.outbox.enabled=true`.
- Live PostgreSQL evidence: `../eia-contract-domain/scripts/live-smoke.ps1`
  creates a contract, records two layers and three participations, proves the
  overlap and 100% rejections, binds, records a cession, proves the capacity
  rejection, then commutes and closes the contract.

## Remaining gaps against SPEC 11.3

- `contract_section`, `contract_version`, `contract_sliding_scale`,
  `contract_premium`, `contract_reinstatement`, `contract_wording`,
  `bordereau_line`/`bordereau_exception`,
  `contract_mass_tort_profile`, and the `contract_loss_aggregate` projection.
- Technical accounts: period-within-contract and non-overlap checks (CTR-07),
  the wider SPEC line-type domain, the `AGREED`/`DISPUTED`/`SUPERSEDED` states,
  approval/authority, and payment settlement
  (`TECHNICAL_ACCOUNT_SETTLEMENT`).
- Commands: endorse, renew, bordereau line parsing (the period-level post is
  implemented), reinstatement application, loss-aggregate projection.
- Outbox delivery has no subscriber, inbox, subscription registry, or gap
  detection (SPEC 7.5.2-7.5.3); the dispatcher and Quartz schedule exist but no
  event is consumed by another vertical.
- Authority limits (SPEC 7.21.3) are represented only by the required `approvedBy`
  evidence on bind and commutation approval.
- Reinsurance calculations and test vectors (SPEC 7.19), Schedule F and
  reinsurance tax (SPEC 7.20, 7.22).
- The internal API still uses the `?tenantId=` query parameter (MISMATCHES M-08)
  and the vertical `ApiError` body (MISMATCHES M-02).
