# Security policy

## Supported versions

Zynqo has not had a stable release yet. Once 1.0.0 is released, security fixes go to the latest
minor release line.

| Version | Supported |
|---|---|
| `0.x` (pre-release) | Best effort |

## Reporting a vulnerability

**Do not open a public issue.** Report privately through GitHub:
<https://github.com/tanmaysinghx/zynqo-spring-boot-starter/security/advisories/new>.

Please include the affected version, configuration, a minimal reproduction and the impact you
expect, for example cross-user response replay, duplicate execution beyond the documented
limitations, or data exposure. Do not include real customer data or credentials.

You can expect an acknowledgement within 5 working days and an assessment with a remediation
plan within 15 working days. Fixes are released as patch versions and credited in the advisory,
unless you prefer to remain anonymous.

## Scope

In scope: the published modules (`zynqo-core`, `zynqo-storage-redis`,
`zynqo-spring-boot-autoconfigure`, `zynqo-spring-boot-starter`) and the release pipeline.

Behaviour documented in [docs/failure-semantics.md](docs/failure-semantics.md) and
[docs/security.md](docs/security.md) is not a vulnerability. That includes duplicate execution
after lease expiry and plaintext response bodies in Redis. Reports that show the documentation is
wrong or incomplete are welcome.
