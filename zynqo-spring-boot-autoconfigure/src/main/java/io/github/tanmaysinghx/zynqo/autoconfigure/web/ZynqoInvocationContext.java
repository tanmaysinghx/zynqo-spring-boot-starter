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
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * An intercepted invocation of a {@code @Zynqo} handler method.
 *
 * @param request the current HTTP request
 * @param method the handler method
 * @param arguments the resolved handler method arguments
 * @param operation the operation name that scopes the idempotency key
 */
public record ZynqoInvocationContext(
    HttpServletRequest request, Method method, Object[] arguments, String operation) {

  /** Validates the components. */
  public ZynqoInvocationContext {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(operation, "operation");
  }
}
