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
package io.github.tanmaysinghx.zynqo.core.error;

/**
 * Stable, machine-readable classification of the failures Zynqo reports to callers.
 *
 * <p>The names are part of the public contract: they appear in Problem Details responses as the
 * {@code code} member and must not change between minor versions.
 */
public enum ZynqoErrorCode {

  /** No idempotency key was supplied for an operation that requires one. */
  KEY_MISSING,

  /** The supplied idempotency key is too long or contains characters outside the allowed set. */
  KEY_INVALID,

  /** No principal could be resolved and anonymous idempotent requests are not allowed. */
  PRINCIPAL_REQUIRED,

  /** The key was already used with a different request payload. */
  FINGERPRINT_MISMATCH,

  /** Another request with the same key is still executing. The caller may retry later. */
  REQUEST_IN_PROGRESS,

  /**
   * An earlier attempt with this key failed in a way that may have produced side effects, or its
   * response could not be stored. The operation is not executed again; the application must
   * reconcile the outcome.
   */
  PREVIOUS_ATTEMPT_FAILED,

  /** The idempotency store could not be reached; execution was refused (fail-closed). */
  STORAGE_UNAVAILABLE,

  /** A stored response exists but can no longer be decoded into the handler's return type. */
  REPLAY_FAILED
}
