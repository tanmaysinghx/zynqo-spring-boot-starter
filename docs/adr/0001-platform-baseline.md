# ADR 0001: Platform baseline and version matrix

- Status: accepted
- Date: 2026-10-30

## Context

Zynqo must build on current, supported versions and avoid inventing coordinates. Versions were
read from Maven Central metadata and from the Spring Boot dependencies BOM on 2026-10-30.

## Decision

| Component | Selected | Rationale |
|---|---|---|
| Java baseline | 21 (`--release 21`) | Requested by the maintainer; current LTS widely deployed; enables records, sealed types and pattern matching |
| Java tested | 21, 25 (CI matrix) | 25 is the newest LTS. Only JDK 21 was available in the development environment. |
| Spring Boot | 4.1.2 | Latest stable on Central (4.2.0 was RC only). Brings Spring Framework 7.0.10, Spring Data Redis 4.1.2, Spring Security 7.1.2, Jackson 3.1.7, Lettuce 7.5.2, Micrometer 1.17.2, JUnit Jupiter 6.0.3, Testcontainers 2.0.5. |
| Spring Boot 3.x | not supported | Boot 4 moved auto-configurations into per-technology modules (for example `DataRedisAutoConfiguration` in `spring-boot-data-redis`) and defaults to Jackson 3 (`tools.jackson`). Supporting both would double the integration surface. |
| Redis | 7.x, tested on 7.4 | Lua effects replication (needed for `TIME` inside scripts) is the only mode since Redis 7 |
| Maven | 3.9.16 via wrapper (`only-script`, SHA-256 pinned); enforcer requires 3.9.6+ | Latest 3.9.x; 4.0 was still a release candidate |
| Formatter | Spotless 3.10.4 + google-java-format 1.36.1 | gjf 1.37.0 fails inside Spotless 3.10.4 (observed); pinned in lockstep |
| Static analysis | SpotBugs 4.10.5 (plugin 4.10.5.0) | Mature bytecode analysis; zero findings required |
| Coverage | JaCoCo 0.8.16 | Supports Java 25 class files |
| Publishing | central-publishing-maven-plugin 0.12.0, maven-gpg-plugin 3.2.8 (BC signer) | Central Portal is the supported path; the BC signer needs no gpg binary |
| SBOM / licenses | CycloneDX 2.9.3, license-maven-plugin 2.7.1 | |
| GitHub Actions | checkout v7.0.1, setup-java v6.1.0, upload-artifact v7.0.2, codeql-action v4.39.1, dependency-review-action v5.0.0, osv-scanner-action v2.7.0, each pinned by commit SHA | Latest releases; SHA pinning prevents tag hijacking |

## Consequences

- Dependabot proposes updates weekly. google-java-format is excluded until Spotless supports newer
  versions.
- The Boot BOM governs every runtime and test dependency. Zynqo declares no runtime versions of
  its own.
