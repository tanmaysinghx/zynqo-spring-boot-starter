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
package io.github.tanmaysinghx.zynqo.core.store;

import java.time.Duration;
import java.util.Objects;

/**
 * Atomic outcome of an attempt to claim an idempotency record.
 *
 * <p>Each variant corresponds to one state of the record at the instant of the claim:
 *
 * <pre>
 * ABSENT ──claim──▶ IN_PROGRESS ──complete──▶ COMPLETED
 *                     │    ▲  │
 *                     │    │  └──fail──▶ FAILED
 *       lease expired │    │
 *                     └────┘ (takeover, fence + 1)
 *                     │
 *                     └──release──▶ ABSENT
 * </pre>
 */
public sealed interface ClaimResult {

  /**
   * The caller now exclusively owns execution: the record was absent, or its previous owner's lease
   * had expired.
   *
   * @param token the ownership token required for every later transition
   * @param recovered {@code true} when ownership was taken over from an expired lease
   */
  record Acquired(OwnershipToken token, boolean recovered) implements ClaimResult {

    /** Validates the result. */
    public Acquired {
      Objects.requireNonNull(token, "token");
    }
  }

  /**
   * The operation already completed with the same fingerprint.
   *
   * @param response the stored response to replay
   */
  record Completed(StoredResponse response) implements ClaimResult {

    /** Validates the result. */
    public Completed {
      Objects.requireNonNull(response, "response");
    }
  }

  /**
   * Another owner holds a valid lease for the same fingerprint.
   *
   * @param leaseRemaining time until the current lease expires, never negative
   */
  record InProgress(Duration leaseRemaining) implements ClaimResult {

    /** Validates the result. */
    public InProgress {
      Objects.requireNonNull(leaseRemaining, "leaseRemaining");
      if (leaseRemaining.isNegative()) {
        throw new IllegalArgumentException("leaseRemaining must not be negative");
      }
    }
  }

  /** The record exists with a different fingerprint. */
  record FingerprintMismatch() implements ClaimResult {}

  /** A previous attempt ended in the {@code FAILED} state and must not be executed again. */
  record Failed() implements ClaimResult {}
}
