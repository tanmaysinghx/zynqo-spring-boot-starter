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

/**
 * Signals that an {@link IdempotencyStore} operation failed because of an infrastructure problem
 * (connection failure, timeout, unexpected record format).
 *
 * <p>When this is thrown the outcome of a state-changing operation is unknown: it may or may not
 * have been applied.
 */
public class ZynqoStorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception.
   *
   * @param message a description that must not contain keys, fingerprints or payload data
   * @param cause the underlying cause, or {@code null}
   */
  public ZynqoStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
