# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- `contract_layer`, `contract_participant`, `contract_term`, `contract_security`,
  `contract_cession`, `contract_bordereau`, and `contract_commutation` tables with
  idempotent DDL, check constraints, unique keys, and foreign keys.
- Internal APIs for layers (with a tower summary), participants, terms (with the
  required-terms view), security, cessions, bordereaux, commutations (propose,
  approve, execute), bind eligibility, and bind.
- Invariants: tower overlap (CTR-02), aggregate limit (CTR-03), shares total 100%
  (CTR-01), required terms by contract type, over-collateralisation approval,
  cession capacity (CTR-04) and source share (CTR-05), security breach block
  (CTR-11), commutation approval and release (CTR-12), contract currency
  (CTR-17), and the ASSUMED book wall.
- Business-key generation (`CES`, `BRD`, `COM`) and bind/commutation timestamps.
- Bind is a command with an approver; `PATCH /status` no longer accepts `BOUND`.
- 121 JUnit tests.

### Changed

- The contract projection adds `boundAt`, `boundBy`, `commutedAt`, and `closedAt`.
- Repository baseline.
