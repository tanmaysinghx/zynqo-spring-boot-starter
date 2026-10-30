# ADR 0002: Interception at the handler method with Spring AOP

- Status: accepted
- Date: 2026-10-30

## Context

Idempotency must cover SpEL keys over handler arguments, respect authorization, record completion
only after transactions commit, and replay responses.

## Options

| Option | SpEL over arguments | Sees committed result | Replay | Complexity |
|---|---|---|---|---|
| Servlet filter caching raw bytes | No (arguments not yet bound) | Yes | Exact bytes | Needs handler lookup; buffers every response |
| `HandlerInterceptor` | No | Partially (`afterCompletion`, response already committed) | Needs response wrapper | Medium |
| **AOP advisor on the handler method** | Yes | Yes (ordered outside `@Transactional`) | Typed object through MVC | Low |

## Decision

Use an infrastructure `Advisor` ordered at 1000: inside Spring Security method interceptors and
outside transaction advice. Validate every annotated handler at startup. Reject invocations that
are not the current request's handler. Register an auto-proxy creator if none exists.

## Consequences

- Replays go through MVC message conversion again (content negotiation, `ResponseBodyAdvice`),
  so they are semantically equal but not byte-for-byte identical. See ADR 0004.
- Headers written directly to `HttpServletResponse` are not replayed.
- WebFlux is out of scope. A reactive implementation would need its own non-blocking engine path.
- An early default order of 0 put the interceptor *outside* `@PreAuthorize`, which would have let
  replays bypass method security. A test caught this before release, and the default became 1000.
