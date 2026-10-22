# eia-contract-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-contract-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Contract
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_contract (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Contract persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Reinsurance contract administration, layers, participations, accounts, and bordereaux.

## Implemented slice

The expert persists the contract header and its children — layers, participants,
terms, security, cessions, bordereaux, commutations, and technical accounts —
under `/internal/v1/contracts`:

- `GET/POST /{id}/layers`, `GET /{id}/layers/summary`
- `GET/POST /{id}/participants`
- `GET/POST /{id}/terms`, `GET /{id}/terms/requirements`
- `GET/POST /{id}/security`
- `GET/POST /{id}/cessions`
- `GET/POST /{id}/bordereaux`, `GET /{id}/bordereaux/{bordereauId}` (legacy
  ingest header)
- `GET/POST /{id}/commutations`, `POST /{id}/commutations/{commutationId}/approve`,
  `POST /{id}/commutations/{commutationId}/execute`
- `GET/POST /{id}/technical-accounts`,
  `PATCH /{id}/technical-accounts/{accountId}/status`,
  `GET/POST /{id}/technical-accounts/{accountId}/lines`,
  `DELETE /{id}/technical-accounts/{accountId}/lines/{lineId}`
- `GET /{id}/bind-eligibility`, `POST /{id}/bind`

The technical-account endpoints are also exposed under the brief's public path,
`/api/v1/contracts/{contractId}/technical-accounts`; both prefixes use the same
controllers and accept the tenant from `X-EIA-Tenant` or the internal `tenantId`
query parameter.

A technical account carries line detail behind its header: the lines endpoint
lists the lines with detailed per-type totals and four bucket totals, adds a
line, and removes one. The implemented line types are `PREMIUM`, `COMMISSION`,
`BROKERAGE`, `PAID_LOSS`, `OUTSTANDING_LOSS`, `IBNR`, `ALAE`, `REINSTATEMENT`,
`CASH_CALL`, `FUNDS_WITHHELD`, `TAX`, `ADJUSTMENT`, and `OTHER`. Each type rolls
into one of four reconciliation buckets: `PREMIUM` -> `premium`; `PAID_LOSS`,
`OUTSTANDING_LOSS`, `IBNR`, `ALAE`, `REINSTATEMENT`, and `CASH_CALL` -> `loss`;
`COMMISSION` and `BROKERAGE` -> `commission`; `FUNDS_WITHHELD`, `TAX`,
`ADJUSTMENT`, and `OTHER` -> `other`. Lines can only be changed while the account
is `DRAFT` (409 `ACCOUNT_NOT_EDITABLE`). Issuing an account that has any lines
requires each bucket total to equal its header component (`premium` = ceded
premium, `loss` = ceded losses, `commission` = commission, `other` = other
charges); otherwise the transition is rejected with 409
`ACCOUNT_LINES_DO_NOT_RECONCILE` and one detail per offending bucket naming the
contributing line types. An account with no lines keeps the legacy header-only
issue path. An issued account settles only when the request names the payment
reference: a non-blank `reference` of at most 120 characters, which is trimmed,
stored as `settlementReference`, and included in the settled event. `ISSUED`
ignores the field.

Security/collateral has a public API:
`GET/POST /api/v1/contracts/{contractId}/security` and
`PATCH /api/v1/contracts/{contractId}/security/{securityId}/status`. A record
carries the security type, the secured and held amounts, the contract currency,
an optional provider party, expiry date, and approval reference. The expert
derives the create status (`ACTIVE`, or `EXPIRED` when the expiry date has
already passed). A live record (`ACTIVE`, `OUTSTANDING`, `PARTIAL`, `REQUIRED`)
can be released or expired, an `EXPIRED` record can still be released, and
`RELEASED` is terminal. Creation writes `contract.security.recorded` and a status
action writes `contract.security.status-changed` through the transactional
outbox. The internal `/internal/v1/contracts/{id}/security` endpoint remains for
the bind and commutation slices and writes the recorded event.

Period bordereaux have their own public API:
`GET/POST /api/v1/contracts/{contractId}/bordereaux` and
`PATCH /api/v1/contracts/{contractId}/bordereaux/{bordereauId}/status`.
A bordereau is received with declared premium, loss, commission, and other-charge
totals; processing it creates or reuses the technical account for the same
contract period and links it. Both bordereau shapes share `contract_bordereau`,
and `IMPLEMENTATION.md` records how they are kept apart.

Cash calls have their own public API:
`GET/POST /api/v1/contracts/{contractId}/cash-calls` and
`PATCH /api/v1/contracts/{contractId}/cash-calls/{cashCallId}/status` (also under
`/internal/v1`). A cash call is requested with a contract period, a strictly
positive amount, a currency, and a due date; its generated call number (`CC`) is
unique per tenant and contract. `REQUESTED` may become `ISSUED` or `CANCELLED`,
and `ISSUED` may become `SETTLED`; terminal statuses are immutable. Settling
requires a non-blank `reference` of at most 120 characters, which is trimmed,
stored as `settlementReference`, and included in the settled event; `ISSUED` and
`CANCELLED` ignore the field.

Bind is a guarded command with an approver; `PATCH /{id}/status` no longer accepts
`BOUND`. Layer overlaps, share totals, required terms, over-collateralisation,
cession capacity, security breach, and commutation approval are enforced here.

Every write also appends its canonical domain event to the transactional outbox
(`contract.contract.created`, `.updated`, `.bound`, `.commuted`,
`contract.cession.recorded`, `contract.security.recorded`,
`contract.security.status-changed`,
`contract.technical-account.created`/`.issued`/`.settled`/`.line-added`/
`.line-removed`,
`contract.bordereau.received`/`.processed`/`.failed`,
`contract.cash-call.requested`/`.issued`/`.settled`/`.cancelled`, and the catalog
lifecycle events). The rows are written in the same transaction; the Quartz
dispatcher runs only when `eia.outbox.enabled=true`. `IMPLEMENTATION.md` records
the invariants, schema decisions, event-name mismatches, and remaining SPEC gaps.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
