# Configuration

All properties are type-safe (`ZynqoProperties`), documented in the generated
`META-INF/spring-configuration-metadata.json` (IDE completion), and validated at startup. Invalid
values fail startup with every problem listed.

## Reference

| Property | Type | Default | Description |
|---|---|---|---|
| `zynqo.enabled` | boolean | `true` | When `false`, no Zynqo bean is created and `@Zynqo` has no effect. |
| `zynqo.interceptor-order` | int | `1000` | Advisor order. The default is inside Spring Security method interceptors (100–600) and outside `@Transactional` (`LOWEST_PRECEDENCE`). Change only with a clear reason. |
| `zynqo.storage.key-prefix` | string | `zynqo:` | Redis key namespace. Use a distinct prefix per application sharing a Redis. |
| `zynqo.key.header-name` | string | `Idempotency-Key` | Header read by the header strategy. |
| `zynqo.key.max-length` | int | `255` | Maximum key length, 1–1024. Keys must be visible US-ASCII (`0x21`–`0x7E`). |
| `zynqo.scope.require-principal` | boolean | `true` | Reject anonymous callers with 403. When `false`, all anonymous callers share one key space per tenant and operation. |
| `zynqo.scope.tenant-header` | string | empty | Header holding the tenant id. Use only a header your gateway sets or validates. |
| `zynqo.execution.lease-duration` | duration | `60s` | Exclusive claim duration. Must exceed worst-case execution time. |
| `zynqo.execution.wait-timeout` | duration | `0s` | How long an in-flight duplicate waits before 409. |
| `zynqo.execution.poll-interval` | duration | `50ms` | Check interval while waiting. |
| `zynqo.execution.on-exception` | `release` \| `mark-failed` | `release` | Record handling when the handler throws (see [failure-semantics.md](failure-semantics.md)). |
| `zynqo.retention.completed-ttl` | duration | `24h` | Replay window for completed responses. |
| `zynqo.retention.failed-ttl` | duration | `24h` | How long a failed key blocks re-execution. |
| `zynqo.response.max-body-size` | data size | `64KB` | Maximum stored body. Larger responses are returned but not stored, and the key is marked failed. |
| `zynqo.response.replay-headers` | list | `Location, Content-Type, Content-Language, ETag, Last-Modified` | `ResponseEntity` headers to store and replay. `Set-Cookie`, `Authorization`, `WWW-Authenticate`, `Proxy-*`, `Cookie`, `Connection`, `Keep-Alive`, `Transfer-Encoding`, `TE`, `Trailer`, `Upgrade`, `Content-Length`, `Date` and `Idempotent-Replayed` are rejected at startup. |

Per-endpoint overrides: `@Zynqo(lease = "...", retention = "...", operation = "...")`.

## Production template

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: 6379
      ssl:
        enabled: true
      username: ${REDIS_USERNAME}
      password: ${REDIS_PASSWORD}
      timeout: 500ms
      connect-timeout: 500ms

zynqo:
  storage:
    key-prefix: "orders-service:idem:"
  execution:
    lease-duration: 30s        # > p99.9 handler latency incl. downstream timeouts
    wait-timeout: 0s
    on-exception: release      # handlers are @Transactional
  retention:
    completed-ttl: 24h         # >= the longest client retry window
    failed-ttl: 24h
  response:
    max-body-size: 64KB
```

Redis server settings that matter for correctness:

| Setting | Recommendation | Why |
|---|---|---|
| `maxmemory-policy` | `noeviction` (or `volatile-ttl` with enough headroom) | Evicted records allow duplicate execution |
| `appendonly` | `yes` | Records survive restarts |
| Replication / failover | Prefer fewer failovers; understand async replication | See [failure-semantics.md](failure-semantics.md#redis-outage-and-failover) |
| ACL | A user restricted to `~<prefix>*` with `+evalsha +eval +script\|load` and the commands the scripts call: `+hget +hmget +hset +hdel +hincrby +pexpire +del +time` (ACL rules also apply inside Lua) | Least privilege |

## Opting out

- Globally: `zynqo.enabled=false`.
- Per environment: set the property in a profile.
- Per endpoint: remove the annotation. There is no "annotated but disabled" mode, by design.

## Overriding components

Declare a bean of the type to replace the default: `IdempotencyStore`, `ZynqoScopeResolver`,
`ZynqoRequestFingerprinter`, `ExceptionClassifier`, `ZynqoObserver`, `ZynqoProblemDetailsAdvice`
or `IdempotencyEngine`. Example, tenant from a JWT claim:

```java
@Bean
ZynqoScopeResolver zynqoScopeResolver() {
    return request -> {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return new ZynqoCaller(jwt.getToken().getClaimAsString("tenant_id"), jwt.getName());
        }
        return new ZynqoCaller("", "");
    };
}
```
