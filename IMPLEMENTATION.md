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
| Security | `contract_security`, `GET/POST .../security` | collateral above the secured obligation requires an approval reference and timestamp; expiry not before effective; CTR-11 breach blocks cessions |
| Cessions | `contract_cession`, `GET/POST .../cessions` | CTR-04 ceded loss within the live tower (contract or named layer); CTR-05 active cessions per source <= 100%; ASSUMED book wall; CTR-17 contract currency; cession inside contract term |
| Bordereaux | `contract_bordereau`, `GET/POST .../bordereaux`, `GET .../bordereaux/{bordereauId}` | coherent period, line counts, contract currency for the declared total, unique bordereau number |
| Commutation | `contract_commutation`, `GET/POST .../commutations`, `POST .../{id}/approve`, `POST .../{id}/execute` | COM-01 approval before execution; CTR-12 release document required; FULL commutation walks COMMUTED -> CLOSED and releases security; one executed commutation per contract |
| Bind | `GET .../bind-eligibility`, `POST .../bind` | every precondition is evaluated and reported; bind requires an approver (CTR-15) and is written atomically with the checks under `SELECT ... FOR UPDATE` |

`PATCH /internal/v1/contracts/{id}/status` no longer accepts `BOUND`. Bind is a
command (`POST .../bind`) because it needs the tower, share, term, security, and
approval checks; the same rule is enforced in the domain service.

## Decisions and deviations

- **`contract_term` rather than `contract_terms` + `contract_wording`.** The task
  brief asks for a term table with `term_code`, `value`, effective dates, and a
  source clause. SPEC 11.3.4 models financial terms as one wide `contract_terms`
  row plus a separate `contract_wording` clause library. The key/value registry
  implemented here covers the financial-term facts as term codes
  (`CEDING_COMMISSION_PCT`, `BROKERAGE_PCT`, `FUNDS_WITHHELD_PCT`, ...) and links
  each to a SPEC 11.3.5 clause code. The wide `contract_terms`,
  `contract_sliding_scale`, `contract_premium`, `contract_reinstatement`,
  `contract_wording`, `contract_section`, `technical_account`, and
  `contract_mass_tort_profile` are still gaps.
- **Cession business references.** `contract_cession` stores `policy_ref` and
  `claim_ref` strings rather than SPEC `source_id` UUIDs, because Contract is an
  ASSUMED-book-only vertical with a hard wall against Policy and Direct Claims.
  `source_vertical` is constrained to `ASSUMED` in the database.
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
  `RequiredTermsTest`, `SecurityControllerTest`, `CessionControllerTest`,
  `BordereauControllerTest`, `CommutationControllerTest`, `BindControllerTest`,
  `ContractStatusTest` — 121 tests, run with `./gradlew test`.
- Live PostgreSQL evidence: `../eia-contract-domain/scripts/live-smoke.ps1`
  creates a contract, records two layers and three participations, proves the
  overlap and 100% rejections, binds, records a cession, proves the capacity
  rejection, then commutes and closes the contract.

## Remaining gaps against SPEC 11.3

- `contract_section`, `contract_version`, `contract_sliding_scale`,
  `contract_premium`, `contract_reinstatement`, `contract_wording`,
  `technical_account`/`technical_account_line`, `bordereau_line`/`bordereau_exception`,
  `contract_mass_tort_profile`, and the `contract_loss_aggregate` projection.
- Commands: endorse, renew, technical-account production, bordereau parsing and
  posting, reinstatement application, cash calls, loss-aggregate projection.
- Events: no outbox, dispatcher, or subscriber; no event is published.
- Authority limits (SPEC 7.21.3) are represented only by the required `approvedBy`
  evidence on bind and commutation approval.
- Reinsurance calculations and test vectors (SPEC 7.19), Schedule F and
  reinsurance tax (SPEC 7.20, 7.22).
- The internal API still uses the `?tenantId=` query parameter (MISMATCHES M-08)
  and the vertical `ApiError` body (MISMATCHES M-02).
