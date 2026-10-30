# ADR 0005: Key scoping and request fingerprinting

- Status: accepted
- Date: 2026-10-30

## Decisions

- The record identity is `SHA-256(tenant, principal, operation, clientKey)`, length-prefixed and
  domain-separated. A client key alone never identifies a record, so cross-user replay is
  impossible by construction. Anonymous requests are rejected unless explicitly allowed.
- The principal comes from `HttpServletRequest#getUserPrincipal()` (populated by Spring Security
  and servlet containers). The tenant comes from an optional, trusted header. Both can be replaced
  with a `ZynqoScopeResolver`.
- The fingerprint is computed over **bound handler arguments** (`@RequestBody`, `@PathVariable`,
  `@RequestParam`, `@ModelAttribute`, unannotated simple types) serialized with the application's
  Jackson mapper and written in canonical form (sorted members, normalized numbers, no
  whitespace), plus the HTTP method and operation. This captures exactly what the handler acts on,
  without buffering raw request bodies for every request.
- Raw keys and payloads are never stored. Response bodies are, and that is documented.

## Consequences

- Payload differences the application ignores (unknown JSON fields) do not cause conflicts.
- Multipart and stream arguments are unsupported and rejected at startup.
- Changing an argument's JSON shape (for example a renamed field) changes fingerprints of
  in-flight keys. Deploys rarely overlap with retention windows enough to matter. When they do,
  give the operation a new name.
