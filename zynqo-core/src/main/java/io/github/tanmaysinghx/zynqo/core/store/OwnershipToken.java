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

import java.util.Objects;

/**
 * Proof of exclusive execution ownership for one claim of an idempotency record.
 *
 * <p>Every successful claim produces a new random {@code owner} value and a strictly increasing
 * {@code fence}. Stores accept a state transition (complete, release, fail) only when both match
 * the current record, so a worker whose lease was taken over cannot overwrite the newer owner's
 * outcome.
 *
 * <p>The fence can also be forwarded to downstream systems that support fencing (for example a
 * database column compared on update) to reject writes from stale workers.
 *
 * @param owner random, unguessable owner identifier
 * @param fence monotonically increasing claim generation, starting at 1
 */
public record OwnershipToken(String owner, long fence) {

  /** Validates the token. */
  public OwnershipToken {
    Objects.requireNonNull(owner, "owner");
    if (owner.isBlank()) {
      throw new IllegalArgumentException("owner must not be blank");
    }
    if (fence < 1) {
      throw new IllegalArgumentException("fence must be positive");
    }
  }

  /** The owner value is a bearer secret for the record; print the fence only. */
  @Override
  public String toString() {
    return "OwnershipToken[fence=" + fence + "]";
  }
}
