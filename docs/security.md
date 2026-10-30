# Security

## Threat model

| Threat | Mitigation |
|---|---|
| User B replays user A's stored response by guessing or stealing A's key | Keys are scoped by principal (and tenant, and operation). B's request with A's key maps to a different record. |
| Anonymous callers sharing a key space | `zynqo.scope.require-principal=true` (default) rejects anonymous calls with 403. |
| Forging the tenant to reach another tenant's records | The principal is always part of the scope; a forged tenant header only partitions the caller's *own* records. Use `tenant-header` only with gateway-validated headers. |
| Bypassing authorization through replay | The interceptor runs after Spring Security's filter chain and inside method security (`@PreAuthorize`, `@PostAuthorize`). Replays are authorized like any other request. Ordering is asserted by tests. |
| Reusing a key with a different payload to get a misleading response | Fingerprint mismatch → 422. |
| Log or metric leakage | Keys, fingerprints and owner tokens print only a short prefix or nothing (`toString()` is redacted). Messages never include keys or payloads. Metrics have fixed tag values only. |
| Deserialization attacks on stored data | No Java serialization. Replays bind JSON only to the handler's declared return type, never to a type named in stored data. |
| SpEL injection | Expressions come from annotations (developer-controlled), are parsed at startup, and are evaluated in a `SimpleEvaluationContext` (read-only data binding): no `T(...)`, constructors, bean references or assignment. |
| Header injection on replay | Header names must be RFC 9110 tokens and values must not contain CR, LF or NUL. Violations are rejected before storage. |
| Credential or session replay | `Set-Cookie`, `Authorization`, `WWW-Authenticate`, `Proxy-*` and hop-by-hop headers are never stored. |
| Oversized records (memory abuse) | Key length ≤ 255 (configurable to 1024), visible ASCII only; body ≤ `max-body-size`. |
| Key-space collisions between applications | Configurable prefix; layout version segment `v1:`. |

## Data stored in Redis

| Data | Form |
|---|---|
| Tenant, principal, operation, client key | **Not stored.** Only `SHA-256` of their length-prefixed concatenation, used as the key name. |
| Request payload | **Not stored.** Only a `SHA-256` fingerprint of its canonical form. |
| Response status, allow-listed headers | Plain text |
| Response body | **Plain JSON** |
| Owner token | Random 128-bit value; a bearer capability for the record during execution |

**Response bodies are sensitive data at rest.** Protect Redis accordingly: TLS
(`spring.data.redis.ssl.enabled`), ACL users restricted to the key prefix, private networking,
encryption at rest, and backups handled as production data. Do not annotate endpoints whose
responses contain secrets (tokens, credentials, full card numbers). Encrypting stored bodies is
planned but not implemented.

Fingerprints of low-entropy payloads can be brute-forced by anyone who can read Redis. That reader
can already read the stored response bodies, so this adds no exposure, but treat fingerprints as
sensitive too.

## Canonicalization limitations

- The fingerprint covers handler **arguments** after Spring MVC binding, not raw bytes. Properties
  ignored by your JSON mapping (unknown fields, `@JsonIgnore`) are not part of the fingerprint:
  two requests that bind to equal objects are the same request.
- Unannotated complex arguments are not fingerprinted. Annotate them with `@ModelAttribute` or
  `@RequestBody`.
- Headers, cookies and the principal are not fingerprinted. The principal is part of the key
  scope instead.
- Multipart bodies and raw streams are not supported (startup failure).
- Floating-point values are compared as decimals after Jackson parsing. `1.0` and `1` are equal;
  values that differ only beyond `double` precision may be indistinguishable if the target type
  is `double`.
- Map ordering never matters. List ordering always does.

## Secret scanning

- Enable GitHub **secret scanning** and **push protection** (Settings → Code security). Both are
  free for public repositories; private repositories require GitHub Secret Protection.
- The build contains no credentials. Release credentials live only in GitHub Actions secrets of
  the protected `maven-central` environment (see [release-process.md](release-process.md)).
- Test credentials are clearly marked test-only constants. The example application requires
  `DEMO_PASSWORD` and has no default.

## Reporting vulnerabilities

See [SECURITY.md](../SECURITY.md).
