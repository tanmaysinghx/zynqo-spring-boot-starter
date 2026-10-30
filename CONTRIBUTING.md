# Contributing to Zynqo

Thank you for helping. Zynqo protects payment-like operations, so correctness, explicit failure
semantics and tests come before features.

## Ground rules

- Discuss behaviour changes in an issue first, especially anything that touches the state machine,
  the Redis scripts, the HTTP contract or the stored record format.
- Keep the public API small. New annotation attributes and properties need a strong case.
- Never weaken a guarantee silently. If a change alters behaviour, update `docs/` and
  `CHANGELOG.md` in the same pull request.
- No secrets, real keys or personal data in code, tests, logs or issues.

## Development setup

Requirements: JDK 21 or newer, and Docker for Testcontainers.

```bash
./mvnw verify                                 # full build: unit + integration tests + coverage gates
./mvnw test -Djacoco.skip.check=true          # fast unit tests, no Docker
./mvnw -Pstatic-analysis verify -DskipTests   # SpotBugs
./mvnw spotless:apply                         # format code and POMs (CI fails on violations)
```

## Code standards

- google-java-format (enforced by Spotless). Apache 2.0 license headers are added automatically.
- Compiler warnings are errors (`-Xlint:all -Werror`).
- SpotBugs must report zero findings. Exclusions go in `.config/spotbugs-exclude.xml` with a
  written justification.
- Library modules keep at least 85% line and 80% branch coverage. Assertions must be meaningful;
  coverage is a gate, not a goal.

## Tests

- Redis behaviour is tested against a real Redis container, never against mocks.
- Concurrency tests coordinate threads with latches and barriers; do not use `Thread.sleep()` to
  wait for other threads. Awaitility polling is acceptable only where real elapsed time is the
  behaviour under test (TTL and lease expiry).
- Name integration tests `*IT` (Failsafe) and unit tests `*Test` (Surefire).

## Commits and pull requests

- Use focused commits with descriptive messages (Conventional Commits style is welcome:
  `feat:`, `fix:`, `docs:`).
- Fill in the pull request template. CI (build on JDK 21 and 25, static analysis, package
  validation) and the Security workflow must pass.
- Maintainers squash or rebase on merge.

## Releases

Maintainers only; see [docs/release-process.md](docs/release-process.md).
