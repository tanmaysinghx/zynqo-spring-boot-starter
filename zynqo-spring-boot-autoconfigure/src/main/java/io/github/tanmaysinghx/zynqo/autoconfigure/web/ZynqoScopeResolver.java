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

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the tenant and principal that scope an idempotency key.
 *
 * <p>The values must come from authenticated, server-verified sources (the security context, a
 * validated token claim, a header set by a trusted gateway). Never derive the principal from
 * client-controlled input: doing so would let one caller read another caller's stored responses.
 *
 * <p>The default implementation uses {@link HttpServletRequest#getUserPrincipal()} (populated by
 * Spring Security and servlet containers) and, if configured, the {@code zynqo.scope.tenant-header}
 * request header. Declare a bean of this type to replace it.
 */
@FunctionalInterface
public interface ZynqoScopeResolver {

  /**
   * Resolves the caller of the current request.
   *
   * @param request the current request
   * @return the caller; use empty strings for absent values
   */
  ZynqoCaller resolve(HttpServletRequest request);
}
