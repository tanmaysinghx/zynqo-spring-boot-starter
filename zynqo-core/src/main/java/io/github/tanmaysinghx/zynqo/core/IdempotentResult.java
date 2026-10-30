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

import java.util.Objects;

/**
 * Result of an idempotent execution together with how it was obtained.
 *
 * @param value the operation result or the replayed result, possibly {@code null}
 * @param outcome how the result was obtained
 * @param <T> the result type
 */
public record IdempotentResult<T>(T value, Outcome outcome) {

  /** Validates the result. */
  public IdempotentResult {
    Objects.requireNonNull(outcome, "outcome");
  }

  /**
   * Returns whether the value was replayed from storage rather than produced by executing the
   * operation.
   *
   * @return {@code true} for a replay
   */
  public boolean replayed() {
    return outcome == Outcome.REPLAYED;
  }
}
