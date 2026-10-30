# ADR 0004: HTTP contract, typed replay and naming

- Status: accepted
- Date: 2026-10-30

## Decisions

1. **Status codes** follow the IETF `Idempotency-Key` header draft where it is specific: 400 for a
   missing or invalid key, 409 for a concurrent request, 422 for a key reused with a different
   payload. The remaining choices: 503 + `Retry-After` when the store is unavailable (fail-closed),
   409 `PREVIOUS_ATTEMPT_FAILED` for failed keys, 403 for anonymous callers (not 401, because Zynqo
   cannot issue a `WWW-Authenticate` challenge), 500 `REPLAY_FAILED` for undecodable records.
2. **Errors** are RFC 9457 Problem Details with `type = urn:zynqo:problem:<code>` and a `code`
   member. The URN scheme avoids implying a hosted documentation domain.
3. **Replay is typed:** the handler result is stored as JSON and, on replay, deserialized into the
   method's declared return type (generic types included) and returned to MVC. This gives correct
   behaviour for `ResponseEntity`, `@ResponseStatus`, content negotiation and response advice,
   without Java serialization or caching arbitrary bytes. The cost: bodies are re-rendered rather
   than byte-identical, and incompatible changes to a return type need a new `operation` name.
4. **Replayed headers** are an allow-list (`Location`, `Content-Type`, `Content-Language`, `ETag`,
   `Last-Modified`), with a hard deny-list for credentials, cookies and hop-by-hop headers.
   `Idempotent-Replayed: true` marks replays.
5. **Annotation naming.** Early design notes used `ttl`. The attribute is `retention`, and the
   lease is a separate `lease` attribute, so the two durations cannot be confused.
6. **Default `wait-timeout` is 0.** Duplicates of in-flight requests are usually client timeouts
   of a slow request; waiting would tie up servlet threads during retry storms. Applications can
   opt in to bounded waiting.
7. **Default exception disposition is `release`.** Most handlers are transactional, so an exception
   implies no committed side effect. Non-transactional handlers should choose `mark-failed` or a
   custom classifier.
