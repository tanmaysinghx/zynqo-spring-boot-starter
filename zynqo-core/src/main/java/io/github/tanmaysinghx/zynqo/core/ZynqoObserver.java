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

/**
 * Receives execution events for metrics and diagnostics.
 *
 * <p>Callbacks receive only bounded, non-sensitive data. Implementations must be thread-safe, fast
 * and must not throw; exceptions thrown by an observer are caught and logged.
 */
public interface ZynqoObserver {

  /** Observer that ignores every event. */
  ZynqoObserver NOOP = new ZynqoObserver() {};

  /**
   * Called once per request with its final outcome.
   *
   * @param outcome the outcome
   */
  default void onOutcome(Outcome outcome) {}

  /**
   * Called after the protected operation ran.
   *
   * @param duration the execution time
   * @param success {@code false} if the operation threw
   */
  default void onExecution(Duration duration, boolean success) {}

  /**
   * Called after every store operation.
   *
   * @param operation the operation
   * @param duration the operation time
   * @param success {@code false} if the store threw
   */
  default void onStorageOperation(StorageOperation operation, Duration duration, boolean success) {}
}
