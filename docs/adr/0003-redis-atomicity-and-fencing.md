# ADR 0003: Redis Lua scripts, owner tokens and fencing

- Status: accepted
- Date: 2026-10-30

## Context

Claims must be exclusive across instances. Stale workers must not overwrite newer results, and
crashes must be recoverable without manual cleanup.

## Decision

- One Redis hash per record, keyed by `prefix + "v1:" + sha256(scope, key)`.
- Four single-key Lua scripts (`claim`, `complete`, `release`, `fail`). Lease expiry uses the
  server clock (`TIME`). Takeover after lease expiry increments a per-record **fence**.
- Every post-claim transition requires an exact match of a random 128-bit owner id **and** the
  fence.
- The record of an in-progress claim lives for `max(lease, completed-ttl)`, so it survives lease
  expiry and the fingerprint is still checked during takeover.
- The fence is exposed to application code (`ZynqoContext.currentFencingToken()`) for downstream
  fencing.
- Zynqo uses the application's `RedisConnectionFactory` and creates no connection pool of its own.

Alternatives rejected: Redisson locks (a heavy dependency, and still no result storage); `SET NX`
plus separate writes (not atomic across fingerprint, state and TTL); `WATCH`/`MULTI` (retry loops
under contention).

## Consequences

- Exactly one owner per valid lease. **Not** exactly-once execution: see
  [concurrency.md](../concurrency.md#the-stale-worker-problem).
- Redis failover with asynchronous replication can lose acknowledged writes. `WAIT` is not used,
  because it would add latency without making the result linearizable.
- Lease renewal is deferred. It narrows but does not close the stale-worker window.
