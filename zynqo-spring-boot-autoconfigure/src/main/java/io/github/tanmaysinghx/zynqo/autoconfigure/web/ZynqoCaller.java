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
package io.github.tanmaysinghx.zynqo.autoconfigure.web;

import java.util.Objects;

/**
 * The tenant and principal an idempotent request is scoped to.
 *
 * @param tenant tenant identifier, or an empty string for single-tenant applications
 * @param principal authenticated principal identifier, or an empty string for an anonymous caller
 */
public record ZynqoCaller(String tenant, String principal) {

  /** Validates the components. */
  public ZynqoCaller {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(principal, "principal");
  }

  /**
   * Returns whether the caller is anonymous.
   *
   * @return {@code true} if no principal was resolved
   */
  public boolean anonymous() {
    return principal.isEmpty();
  }

  /** Tenant and principal identifiers are personal or customer data; never print them. */
  @Override
  public String toString() {
    return "ZynqoCaller[anonymous=" + anonymous() + "]";
  }
}
