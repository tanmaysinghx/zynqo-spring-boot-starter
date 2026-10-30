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

/** What happens to an idempotency record when the protected operation throws. */
public enum ExceptionDisposition {

  /**
   * Delete the record so that a retry with the same key executes the operation again.
   *
   * <p>Correct when the failure guarantees that no side effect survived, for example when the
   * operation runs in a database transaction that was rolled back, or failed validation before any
   * side effect.
   */
  RELEASE,

  /**
   * Keep the record in the {@code FAILED} state. Retries with the same key are rejected with {@link
   * io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode#PREVIOUS_ATTEMPT_FAILED} until the
   * record expires, so a possibly partially applied operation is never executed twice. The
   * application must reconcile the outcome.
   */
  MARK_FAILED
}
