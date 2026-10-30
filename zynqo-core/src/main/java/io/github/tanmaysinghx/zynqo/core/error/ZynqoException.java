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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Signals that Zynqo refused or could not complete an idempotent request.
 *
 * <p>Messages are written for API clients: they never contain idempotency keys, fingerprints,
 * principals, tenants or payload data.
 */
public class ZynqoException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ZynqoErrorCode code;
  private final transient Duration retryAfter;

  /**
   * Creates an exception without retry guidance.
   *
   * @param code the error classification
   * @param message a client-safe message
   */
  public ZynqoException(ZynqoErrorCode code, String message) {
    this(code, message, null, null);
  }

  /**
   * Creates an exception.
   *
   * @param code the error classification
   * @param message a client-safe message
   * @param retryAfter when the caller may retry, or {@code null} if retrying will not help
   * @param cause the underlying cause, or {@code null}
   */
  public ZynqoException(ZynqoErrorCode code, String message, Duration retryAfter, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.retryAfter = retryAfter;
  }

  /**
   * Returns the error classification.
   *
   * @return the error code
   */
  public ZynqoErrorCode code() {
    return code;
  }

  /**
   * Returns when the caller may retry, if retrying can succeed.
   *
   * @return the retry delay
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
