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
terms, security, cessions, bordereaux, and commutations — under
`/internal/v1/contracts`:

- `GET/POST /{id}/layers`, `GET /{id}/layers/summary`
- `GET/POST /{id}/participants`
- `GET/POST /{id}/terms`, `GET /{id}/terms/requirements`
- `GET/POST /{id}/security`
- `GET/POST /{id}/cessions`
- `GET/POST /{id}/bordereaux`, `GET /{id}/bordereaux/{bordereauId}`
- `GET/POST /{id}/commutations`, `POST /{id}/commutations/{commutationId}/approve`,
  `POST /{id}/commutations/{commutationId}/execute`
- `GET /{id}/bind-eligibility`, `POST /{id}/bind`

Bind is a guarded command with an approver; `PATCH /{id}/status` no longer accepts
`BOUND`. Layer overlaps, share totals, required terms, over-collateralisation,
cession capacity, security breach, and commutation approval are enforced here.

Every write also appends its canonical domain event to the transactional outbox
(`contract.contract.created`, `.updated`, `.bound`, `.commuted`,
`contract.cession.recorded`, and the catalog lifecycle events). The rows are
written in the same transaction; the Quartz dispatcher runs only when
`eia.outbox.enabled=true`. `IMPLEMENTATION.md` records the invariants, schema
decisions, event-name mismatches, and remaining SPEC gaps.

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
