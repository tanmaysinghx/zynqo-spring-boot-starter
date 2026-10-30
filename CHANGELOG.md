# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every release needs a
`## [X.Y.Z] - YYYY-MM-DD` section; the release workflow refuses to publish without one.

## [Unreleased]

### Added

- `@Zynqo` annotation for Spring MVC handler methods with header (`Idempotency-Key`) and SpEL key
  strategies, per-endpoint `lease`, `retention` and `operation`.
- Framework-independent idempotency engine: atomic claims, bounded duplicate waiting, fail-closed
  behaviour on store errors, `release` / `mark-failed` exception dispositions, fencing tokens via
  `ZynqoContext`.
- Redis store using single-key Lua scripts with owner and fence checked transitions, server-clock
  leases and TTL-based retention.
- Spring Boot 4.1 auto-configuration, configuration metadata, startup validation of annotated
  handlers, RFC 9457 Problem Details and Micrometer metrics.
- Keys scoped by tenant, principal and operation; canonical JSON request fingerprints.
- Starter, multi-instance integration tests, example orders application.
- CI, security scanning, snapshot and Maven Central release workflows.
