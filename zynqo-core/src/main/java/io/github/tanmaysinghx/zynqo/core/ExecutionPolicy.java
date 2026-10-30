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
package io.github.tanmaysinghx.zynqo.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Timing and size limits for one idempotent execution.
 *
 * <p>The durations are deliberately distinct:
 *
 * <ul>
 *   <li>{@code lease} bounds how long a claim stays exclusive. After it expires, another request
 *       may take over execution. It must exceed the worst-case execution time.
 *   <li>{@code completedRetention} bounds how long a completed response is replayed.
 *   <li>{@code failedRetention} bounds how long a failed record blocks re-execution.
 *   <li>{@code waitTimeout} bounds how long a duplicate request waits for an in-progress execution
 *       before being rejected. Zero rejects immediately.
 *   <li>{@code pollInterval} is the delay between checks while waiting.
 * </ul>
 *
 * <p>Backend operation timeouts are a property of the storage client, not of this policy.
 *
 * @param lease claim lease duration
 * @param completedRetention completed record retention
 * @param failedRetention failed record retention
 * @param waitTimeout maximum wait for an in-progress duplicate
 * @param pollInterval delay between checks while waiting
 * @param maxResponseBytes maximum encoded body size that is stored
 */
public record ExecutionPolicy(
    Duration lease,
    Duration completedRetention,
    Duration failedRetention,
    Duration waitTimeout,
    Duration pollInterval,
    int maxResponseBytes) {

  /** Validates the policy. */
  public ExecutionPolicy {
    requirePositive(lease, "lease");
    requirePositive(completedRetention, "completedRetention");
    requirePositive(failedRetention, "failedRetention");
    Objects.requireNonNull(waitTimeout, "waitTimeout");
    if (waitTimeout.isNegative()) {
      throw new IllegalArgumentException("waitTimeout must not be negative");
    }
    requirePositive(pollInterval, "pollInterval");
    if (maxResponseBytes < 1) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
  }

  /**
   * Returns the retention of the in-progress record: never shorter than the lease, so a crashed
   * owner's record survives until its lease can be taken over.
   *
   * @return the in-progress record time-to-live
   */
  public Duration inProgressRecordTtl() {
    return lease.compareTo(completedRetention) >= 0 ? lease : completedRetention;
  }

  /**
   * Returns a copy with a different lease.
   *
   * @param lease the new lease
   * @return the new policy
   */
  public ExecutionPolicy withLease(Duration lease) {
    return new ExecutionPolicy(
        lease, completedRetention, failedRetention, waitTimeout, pollInterval, maxResponseBytes);
  }

  /**
   * Returns a copy with a different completed-record retention.
   *
   * @param completedRetention the new retention
   * @return the new policy
   */
  public ExecutionPolicy withCompletedRetention(Duration completedRetention) {
    return new ExecutionPolicy(
        lease, completedRetention, failedRetention, waitTimeout, pollInterval, maxResponseBytes);
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
