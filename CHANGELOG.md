# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- `contract_layer`, `contract_participant`, `contract_term`, `contract_security`,
  `contract_cession`, `contract_bordereau`, `contract_commutation`, and
  `contract_technical_account` tables with idempotent DDL, check constraints,
  unique keys, and foreign keys.
- Internal APIs for layers (with a tower summary), participants, terms (with the
  required-terms view), security, cessions, bordereaux, commutations (propose,
  approve, execute), technical accounts (create, newest-period-first list,
  DRAFT -> ISSUED -> SETTLED status), bind eligibility, and bind. The technical
  account resource is also exposed at the brief's
  `/api/v1/contracts/{contractId}/technical-accounts` path.
- Invariants: tower overlap (CTR-02), aggregate limit (CTR-03), shares total 100%
  (CTR-01), required terms by contract type, over-collateralisation approval,
  cession capacity (CTR-04) and source share (CTR-05), security breach block
  (CTR-11), commutation approval and release (CTR-12), contract currency
  (CTR-17), and the ASSUMED book wall.
- Business-key generation (`CES`, `BRD`, `COM`, `TA`) and bind/commutation
  timestamps.
- Technical-account balance convention
  (`cededPremium - commission - cededLosses + otherCharges`): positive is due
  from the cedent to the reinsurer. Periods are unique per tenant and contract;
  amounts must be non-negative and the currency three letters.
- Bind is a command with an approver; `PATCH /status` no longer accepts `BOUND`.
- Transactional outbox production through `com.eia.platform.libs.outbox`:
  `outbox_event` DDL, `contract.contract.created`, `.updated`, `.bound`,
  `.signed`, `.endorsed`, `.expired`, `.cancelled`, `.commuted`,
  `contract.cession.recorded`, and `contract.technical-account.created`/`.issued`/
  `.settled`, written in the aggregate transaction.
- Quartz outbox dispatch job, enabled only when `eia.outbox.enabled=true`, plus
  the `eia.outbox` configuration block.
- 162 JUnit tests, including outbox payload, rollback, failure-atomicity, and
  scheduling tests.

### Changed

- The contract projection adds `boundAt`, `boundBy`, `commutedAt`, and `closedAt`.
- Repository baseline.
