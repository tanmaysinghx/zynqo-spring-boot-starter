/*
 * Copyright 2026 the Zynqo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.tanmaysinghx.zynqo.autoconfigure.metrics;

import io.github.tanmaysinghx.zynqo.core.Outcome;
import io.github.tanmaysinghx.zynqo.core.StorageOperation;
import io.github.tanmaysinghx.zynqo.core.ZynqoObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/**
 * Publishes Zynqo events as Micrometer meters.
 *
 * <p>All tags have small, fixed value sets. Keys, principals, tenants, routes and payloads are
 * never used as tag values.
 *
 * <ul>
 *   <li>{@code zynqo.requests} (counter): {@code outcome} = one of {@link Outcome}, lowercase;
 *   <li>{@code zynqo.execution.duration} (timer): {@code result} = {@code success|failure};
 *   <li>{@code zynqo.storage.duration} (timer): {@code operation}, {@code result};
 *   <li>{@code zynqo.storage.errors} (counter): {@code operation}.
 * </ul>
 */
final class MicrometerZynqoObserver implements ZynqoObserver {

  private final MeterRegistry registry;

  MicrometerZynqoObserver(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void onOutcome(Outcome outcome) {
    Counter.builder("zynqo.requests")
        .description("Idempotent requests by outcome")
        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
        .register(registry)
        .increment();
  }

  @Override
  public void onExecution(Duration duration, boolean success) {
    Timer.builder("zynqo.execution.duration")
        .description("Execution time of protected operations")
        .tag("result", success ? "success" : "failure")
        .register(registry)
        .record(duration);
  }

  @Override
  public void onStorageOperation(StorageOperation operation, Duration duration, boolean success) {
    Timer.builder("zynqo.storage.duration")
        .description("Latency of idempotency store operations")
        .tag("operation", operation.tagValue())
        .tag("result", success ? "success" : "failure")
        .register(registry)
        .record(duration);
    if (!success) {
      Counter.builder("zynqo.storage.errors")
          .description("Failed idempotency store operations")
          .tag("operation", operation.tagValue())
          .register(registry)
          .increment();
    }
  }
}
