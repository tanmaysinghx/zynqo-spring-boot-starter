# Zynqo example: Orders API

A small Spring Boot 4 application showing Zynqo with:

- `POST /api/orders` protected by `@Zynqo` (header strategy);
- `GET /api/orders/{id}`;
- H2 persistence with a **unique `(owner, reference)` constraint**, the business-level guard that
  backs Zynqo when a lease expires;
- HTTP Basic users `alice` and `bob` (so you can see that keys are scoped per user);
- Actuator health and metrics (`zynqo.requests`, ...).

## Run

```bash
cd zynqo-examples/zynqo-example-api
docker compose up -d                       # Redis 7.4 on 127.0.0.1:6379 with AOF persistence
export DEMO_PASSWORD=choose-a-password     # required; there is no default
../../mvnw -f ../../pom.xml install -DskipTests   # first time only: builds the library modules
../../mvnw spring-boot:run
```

In another terminal:

```bash
export DEMO_PASSWORD=choose-a-password
./scripts/demo.sh
```

## What the demo shows

| Step | Request | Expected |
|---|---|---|
| 1 | first `POST` with a new key | `201`, order created |
| 2 | same key, same body | `201`, same order, `Idempotent-Replayed: true` |
| 3 | same key, different quantity | `422` `FINGERPRINT_MISMATCH` |
| 4 | same key as `bob` | `201`, a separate order for bob |
| 5 | no key | `400` `KEY_MISSING` |
| 6 | new key, same business reference | `409` from the database unique constraint |
| 7 | 10 concurrent requests, one key | exactly one order; other responses are `409` (in progress) or replayed `201` |
| 8 | `docker compose stop redis`, then a request | `503` `STORAGE_UNAVAILABLE`, `Retry-After: 1`; no order created |

Manual request:

```bash
curl -i -u alice:$DEMO_PASSWORD \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"reference":"r-1","sku":"book-42","quantity":2}' \
  http://localhost:8080/api/orders
```

Metrics: `curl -u alice:$DEMO_PASSWORD localhost:8080/actuator/metrics/zynqo.requests`.

## Tests

`ExampleApplicationIT` starts the application on a random port against a Redis Testcontainer and
runs the scenarios above, including 40 concurrent requests:

```bash
../../mvnw -f ../../pom.xml verify -pl zynqo-examples/zynqo-example-api -am
```
