# Observability

## Metrics

Published when a Micrometer `MeterRegistry` bean exists (for example with
`spring-boot-starter-actuator`). Names follow Micrometer conventions. Prometheus renders them as
`zynqo_requests_total`, `zynqo_execution_duration_seconds_*`, `zynqo_storage_duration_seconds_*`
and `zynqo_storage_errors_total`.

| Meter | Type | Tags (complete value sets) |
|---|---|---|
| `zynqo.requests` | counter | `outcome` = `executed`, `recovered`, `replayed`, `in_progress`, `fingerprint_mismatch`, `previously_failed`, `storage_unavailable`, `execution_failed`, `not_stored`, `completion_rejected`, `completion_error`, `replay_failed` |
| `zynqo.execution.duration` | timer | `result` = `success`, `failure` |
| `zynqo.storage.duration` | timer | `operation` = `claim`, `complete`, `release`, `fail`; `result` = `success`, `failure` |
| `zynqo.storage.errors` | counter | `operation` |

Requests rejected before reaching the engine (missing or invalid key, anonymous caller) are not
counted in `zynqo.requests`; they appear in your HTTP server metrics as 400/403. To break metrics
down per endpoint, use the standard `http.server.requests` metric (tagged by URI template), not
Zynqo tags.

## Dashboards

| Panel | Query (PromQL) |
|---|---|
| Outcome rate | `sum by (outcome) (rate(zynqo_requests_total[5m]))` |
| Replay ratio | `sum(rate(zynqo_requests_total{outcome="replayed"}[5m])) / sum(rate(zynqo_requests_total[5m]))` |
| Store latency p99 | `histogram_quantile(0.99, sum by (le, operation) (rate(zynqo_storage_duration_seconds_bucket[5m])))` (enable histograms with `management.metrics.distribution.percentiles-histogram.zynqo.storage.duration=true`) |
| Execution latency vs lease | p99 of `zynqo_execution_duration_seconds`, plotted against the configured lease |

## Alerts

| Alert | Condition | Meaning |
|---|---|---|
| Store unavailable | `rate(zynqo_requests_total{outcome="storage_unavailable"}[5m]) > 0` | Requests refused with 503 |
| Completion errors | `increase(zynqo_requests_total{outcome="completion_error"}[15m]) > 0` | Executed operations whose result was not recorded; duplicates are possible after lease expiry |
| Lease takeovers | `increase(zynqo_requests_total{outcome=~"recovered|completion_rejected"}[15m]) > 0` | Handlers exceeding their lease, or crashed instances; **duplicate execution likely** |
| Not stored | `increase(zynqo_requests_total{outcome="not_stored"}[1h]) > 0` | Responses larger than `max-body-size` or not serializable |
| Replay failures | `increase(zynqo_requests_total{outcome="replay_failed"}[15m]) > 0` | Incompatible return-type change between deploys |

## Logging

SLF4J logger `io.github.tanmaysinghx.zynqo`:

| Level | Event |
|---|---|
| INFO | `Zynqo protects N handler method(s)` at startup |
| WARN | Store unavailable during claim (request refused); lease takeover; stale completion rejected; observer failure |
| ERROR | Completion or release failed after execution; response cannot be stored; stored response cannot be decoded |

Log lines identify records by a 12-character prefix of the key digest (`IdempotencyKey[3fa1...]`)
and claims by fence (`OwnershipToken[fence=2]`). Raw keys, payloads, principals and owner tokens
are never logged.

## Tracing

Zynqo does not create spans. With Micrometer Tracing, the Lettuce client's own observations show
the Redis calls inside the request span. The fencing token is available to application code
(`ZynqoContext.currentFencingToken()`) if you want to add it as a span attribute.
