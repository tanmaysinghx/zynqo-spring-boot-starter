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
package io.github.tanmaysinghx.zynqo.core.identity;

import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;

/**
 * Validates client-supplied idempotency keys.
 *
 * <p>A valid key is between 1 and {@code maxLength} characters of visible US-ASCII ({@code 0x21} to
 * {@code 0x7E}). This admits UUIDs, ULIDs and other common formats while rejecting whitespace,
 * control characters and non-ASCII input that could be used for log injection or homoglyph
 * confusion.
 */
public final class ClientKeyValidator {

  /** Upper bound accepted for {@code maxLength}; keeps header and SpEL keys reasonably small. */
  public static final int MAX_SUPPORTED_LENGTH = 1024;

  private final int maxLength;

  /**
   * Creates a validator.
   *
   * @param maxLength maximum accepted key length, between 1 and {@value #MAX_SUPPORTED_LENGTH}
   */
  public ClientKeyValidator(int maxLength) {
    if (maxLength < 1 || maxLength > MAX_SUPPORTED_LENGTH) {
      throw new IllegalArgumentException(
          "maxLength must be between 1 and " + MAX_SUPPORTED_LENGTH + " but was " + maxLength);
    }
    this.maxLength = maxLength;
  }

  /**
   * Validates a client key.
   *
   * @param key the key, possibly {@code null}
   * @return the key, unchanged
   * @throws ZynqoException with {@link ZynqoErrorCode#KEY_MISSING} when the key is null or empty,
   *     or {@link ZynqoErrorCode#KEY_INVALID} when it is too long or contains invalid characters
   */
  public String validate(String key) {
    if (key == null || key.isEmpty()) {
      throw new ZynqoException(ZynqoErrorCode.KEY_MISSING, "An idempotency key is required.");
    }
    if (key.length() > maxLength) {
      throw new ZynqoException(
          ZynqoErrorCode.KEY_INVALID,
          "The idempotency key must not be longer than " + maxLength + " characters.");
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 0x21 || c > 0x7E) {
        throw new ZynqoException(
            ZynqoErrorCode.KEY_INVALID,
            "The idempotency key may contain only visible US-ASCII characters.");
      }
    }
    return key;
  }

  /**
   * Returns the configured maximum key length.
   *
   * @return the maximum key length
   */
  public int maxLength() {
    return maxLength;
  }
}
