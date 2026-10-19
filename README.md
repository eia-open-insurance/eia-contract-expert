# eia-contract-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-contract-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Contract
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_contract (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-open-insurance-libs

## Purpose

Contract persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Assumed reinsurance contract administration, layers, participations, accounts, and bordereaux.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and CI.
- Contain no business logic in shared libraries or front-end remotes.

## Required checks

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
