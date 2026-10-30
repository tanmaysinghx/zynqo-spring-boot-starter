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
 * The authorization boundary an idempotency key belongs to.
 *
 * <p>A client key is meaningful only inside its scope: the same client key used by two principals,
 * two tenants or against two operations identifies two unrelated records.
 *
 * @param tenant the tenant identifier, or an empty string for single-tenant applications
 * @param principal the authenticated principal identifier, or an empty string for anonymous callers
 *     when anonymous use has been explicitly allowed
 * @param operation the stable name of the protected operation
 */
public record IdempotencyScope(String tenant, String principal, String operation) {

  /** Validates the scope components. */
  public IdempotencyScope {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(operation, "operation");
    if (operation.isBlank()) {
      throw new IllegalArgumentException("operation must not be blank");
    }
  }

  /**
   * Returns a copy of this scope that belongs to a different operation.
   *
   * @param operation the new operation name
   * @return the new scope
   */
  public IdempotencyScope withOperation(String operation) {
    return new IdempotencyScope(tenant, principal, operation);
  }

  /** Never print tenant or principal identifiers; they are personal or customer data. */
  @Override
  public String toString() {
    return "IdempotencyScope[operation=" + operation + "]";
  }
}
