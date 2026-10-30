# Failure semantics

Zynqo never retries a protected operation. When it cannot establish ownership safely, it refuses
to execute (fail-closed). When an operation has already run, the caller always receives the real
result, even if recording it failed.

## Matrix

| # | Failure | What the caller of *this* request sees | Record afterwards | What a retry with the same key sees | Reconciliation needed? |
|---|---|---|---|---|---|
| 1 | Request validation fails (`@Valid`, conversion) | 400 from Spring MVC | none; the handler was never invoked, so no claim | normal execution | no |
| 2 | Missing, invalid or repeated key; anonymous caller | 400 / 400 / 403 Problem Details | none | same error | no |
| 3 | Redis unavailable or timing out **before** claim | 503 `STORAGE_UNAVAILABLE`, `Retry-After: 1` | unchanged | normal processing once Redis is back | no; not executed |
| 4 | Handler throws, disposition `release` (default) | the application's error response | deleted | **executes again** | only if the exception happened *after* a non-transactional side effect |
| 5 | Handler throws, disposition `mark-failed` | the application's error response | `FAILED` for `failed-ttl` | 409 `PREVIOUS_ATTEMPT_FAILED` | yes; the client must check the outcome and use a new key |
| 6 | Result not serializable, or larger than `max-body-size` | the real response (200/201 ...) | `FAILED` | 409 `PREVIOUS_ATTEMPT_FAILED` | yes; the operation ran but cannot be replayed |
| 7 | Redis fails **after** execution (completion write) | the real response; logged at ERROR, `outcome=completion_error` | `IN_PROGRESS` until the lease expires | 409 until the lease expires, then **executes again** | yes; use business guards |
| 8 | Lease expired while running; another instance took over | the real response; logged at WARN, `outcome=completion_rejected` | the new owner's result | the new owner's response | yes; the operation ran twice |
| 9 | JVM or container killed mid-execution | connection reset | `IN_PROGRESS` until the lease expires | 409, then executes after lease expiry | depends on whether side effects happened |
| 10 | Graceful shutdown interrupts the handler | connection closed or 5xx | released (the interruption is an exception, case 4) | executes again | as case 4 |
| 11 | Network partition between app and Redis | as case 3 or 7 depending on timing | | | as case 3 or 7 |
| 12 | Client cancels / disconnects mid-request | nothing (connection gone) | the servlet container normally keeps processing and the record completes (container behaviour; not tested) | replay | no |
| 13 | Redis restarts **with** persistence (RDB on shutdown, AOF) | — | preserved (tested) | replay | no |
| 14 | Redis restarts **without** persistence, fails over to a lagging replica, or evicts keys | — | lost (tested for restart) | **executes again** | yes; configure persistence and `noeviction` |
| 15 | Stored response cannot be decoded (return type changed incompatibly between deploys) | 500 `REPLAY_FAILED` | unchanged | same until retention expires | change the `operation` name when making incompatible changes |
| 16 | `ExceptionClassifier` throws | the original exception | `FAILED` (safe fallback) | 409 | yes |
| 17 | Waiting duplicate interrupted | 409 `REQUEST_IN_PROGRESS`; interrupt flag preserved | unchanged | normal | no |

Cases 3, 4, 5, 6, 7, 8, 14 and 16 are covered by tests in `IdempotencyEngineTest`,
`RedisOutageIT`, `ZynqoHttpContractIT`, `ZynqoStorageOutageIT` and `MultiInstanceIT`. Cases 9 and
11 are covered by the closest in-process equivalents (`MultiInstanceIT` destroys an instance's
Redis connection pool mid-execution, and pauses or stops Redis containers).

## Choosing an exception disposition

| Disposition | Use when | Risk |
|---|---|---|
| `release` (default) | The operation is transactional (rollback on exception) or exceptions occur before side effects | A non-transactional side effect followed by an exception is repeated on retry |
| `mark-failed` | Side effects may have happened before the exception (remote calls, messages) | Clients must reconcile and use a new key |

For mixed behaviour, provide an `ExceptionClassifier` bean:

```java
@Bean
ExceptionClassifier zynqoExceptionClassifier() {
    return failure -> failure instanceof BusinessRuleViolation
            ? ExceptionDisposition.RELEASE
            : ExceptionDisposition.MARK_FAILED;
}
```

## Timeouts

| Setting | Bounds | Default |
|---|---|---|
| `zynqo.execution.lease-duration` / `@Zynqo(lease)` | how long a claim is exclusive | 60 s |
| `zynqo.retention.completed-ttl` / `@Zynqo(retention)` | how long responses are replayed | 24 h |
| `zynqo.retention.failed-ttl` | how long a failed key blocks re-execution | 24 h |
| `zynqo.execution.wait-timeout` | how long a duplicate waits | 0 |
| `spring.data.redis.timeout` | every Redis command | Lettuce default 60 s; **set it** (for example 500 ms) |

Zynqo does not impose an execution timeout on handlers. A handler that outlives its lease keeps
running (case 8).

## Redis outage and failover

- **Outage:** requests are refused with 503 within the Redis command timeout. Nothing executes.
- **Recovery:** the Lettuce client reconnects automatically. Verified by pausing and restarting a
  Redis container.
- **Failover (Sentinel or Cluster):** Redis replication is asynchronous. A claim or completion
  acknowledged by the old primary may be missing on the new primary, which is case 14. Zynqo does
  not use `WAIT`, so a failover can permit a duplicate execution.
