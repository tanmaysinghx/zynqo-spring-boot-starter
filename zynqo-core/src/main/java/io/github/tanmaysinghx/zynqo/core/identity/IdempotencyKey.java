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
 * Collision-resistant, opaque identifier of an idempotency record.
 *
 * <p>The identifier is the hex-encoded SHA-256 digest of the scope (tenant, principal, operation)
 * and the client-supplied key. The raw client key and the scope values are never stored or logged;
 * only this digest reaches the storage backend.
 *
 * @param id lowercase hex SHA-256 digest (64 characters)
 */
public record IdempotencyKey(String id) {

  private static final String DOMAIN = "zynqo/key/v1";

  /** Validates the identifier format. */
  public IdempotencyKey {
    Objects.requireNonNull(id, "id");
    if (!id.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("id must be a lowercase hex SHA-256 digest");
    }
  }

  /**
   * Derives the record identifier for a client key within a scope.
   *
   * @param scope the authorization scope
   * @param clientKey the validated client-supplied key
   * @return the derived key
   */
  public static IdempotencyKey derive(IdempotencyScope scope, String clientKey) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(clientKey, "clientKey");
    return new IdempotencyKey(
        new Sha256(DOMAIN)
            .field(scope.tenant())
            .field(scope.principal())
            .field(scope.operation())
            .field(clientKey)
            .hex());
  }

  /** Prints a short prefix only, enough to correlate log lines without exposing the full id. */
  @Override
  public String toString() {
    return "IdempotencyKey[" + id.substring(0, 12) + "...]";
  }
}
