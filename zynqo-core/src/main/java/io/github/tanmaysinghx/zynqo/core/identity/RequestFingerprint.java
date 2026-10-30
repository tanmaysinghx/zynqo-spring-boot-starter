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

import java.util.Objects;

/**
 * SHA-256 digest of the canonical representation of a request.
 *
 * <p>Two requests that reuse an idempotency key must have equal fingerprints; otherwise the second
 * request is rejected as a conflict. Raw request content is never stored, only this digest.
 *
 * @param value lowercase hex SHA-256 digest (64 characters)
 */
public record RequestFingerprint(String value) {

  private static final String DOMAIN = "zynqo/fingerprint/v1";

  /** Validates the fingerprint format. */
  public RequestFingerprint {
    Objects.requireNonNull(value, "value");
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("value must be a lowercase hex SHA-256 digest");
    }
  }

  /**
   * Starts building a fingerprint from an ordered sequence of named components.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Fingerprints may be correlated with request content; never print them in full. */
  @Override
  public String toString() {
    return "RequestFingerprint[" + value.substring(0, 12) + "...]";
  }

  /** Builds a fingerprint from an ordered sequence of named components. Not thread-safe. */
  public static final class Builder {

    private final Sha256 sha = new Sha256(DOMAIN);
    private boolean built;

    private Builder() {}

    /**
     * Adds a named text component.
     *
     * @param name the component name
     * @param value the component value
     * @return this builder
     */
    public Builder add(String name, String value) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      checkNotBuilt();
      sha.field(name).field(value);
      return this;
    }

    /**
     * Adds a named binary component.
     *
     * @param name the component name
     * @param value the component bytes
     * @return this builder
     */
    public Builder add(String name, byte[] value) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      checkNotBuilt();
      sha.field(name).field(value);
      return this;
    }

    /**
     * Completes the digest. The builder cannot be reused afterwards.
     *
     * @return the fingerprint
     */
    public RequestFingerprint build() {
      checkNotBuilt();
      built = true;
      return new RequestFingerprint(sha.hex());
    }

    private void checkNotBuilt() {
      if (built) {
        throw new IllegalStateException("fingerprint already built");
      }
    }
  }
}
