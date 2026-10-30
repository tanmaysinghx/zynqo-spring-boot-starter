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

import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoProperties;
import io.github.tanmaysinghx.zynqo.core.IdempotencyEngine;
import io.github.tanmaysinghx.zynqo.core.IdempotentResult;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import io.github.tanmaysinghx.zynqo.core.identity.ClientKeyValidator;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyScope;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/** Runs an intercepted {@code @Zynqo} handler invocation through the idempotency engine. */
final class ZynqoInvocationHandler {

  /** Response header set on replayed responses. */
  static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private final IdempotencyEngine engine;
  private final ZynqoProperties properties;
  private final JsonMapper mapper;
  private final ZynqoScopeResolver scopeResolver;
  private final ZynqoRequestFingerprinter fingerprinter;
  private final ClientKeyValidator keyValidator;
  private final Map<Method, OperationMetadata> metadata = new ConcurrentHashMap<>();

  ZynqoInvocationHandler(
      IdempotencyEngine engine,
      ZynqoProperties properties,
      JsonMapper mapper,
      ZynqoScopeResolver scopeResolver,
      ZynqoRequestFingerprinter fingerprinter) {
    this.engine = engine;
    this.properties = properties;
    this.mapper = mapper;
    this.scopeResolver = scopeResolver;
    this.fingerprinter = fingerprinter;
    this.keyValidator = new ClientKeyValidator(properties.getKey().getMaxLength());
  }

  /** Returns the validated metadata of a handler method. */
  OperationMetadata metadata(Method method) {
    return metadata.computeIfAbsent(method, m -> OperationMetadata.create(m, properties, mapper));
  }

  Object invoke(MethodInvocation invocation) throws Throwable {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) {
      throw new IllegalStateException(
          "@Zynqo method "
              + invocation.getMethod()
              + " was invoked outside a Spring MVC request; @Zynqo is supported only on Spring MVC"
              + " handler methods");
    }
    HttpServletRequest request = servlet.getRequest();
    Method method = handlerMethod(invocation, request);
    OperationMetadata operation = metadata(method);
    Object[] arguments = invocation.getArguments();

    String clientKey = keyValidator.validate(operation.clientKey(arguments, request));
    ZynqoCaller caller = scopeResolver.resolve(request);
    if (caller.anonymous() && properties.getScope().isRequirePrincipal()) {
      throw new ZynqoException(
          ZynqoErrorCode.PRINCIPAL_REQUIRED,
          "Idempotent requests to this operation require an authenticated caller.");
    }
    String operationName = operation.operation(request);
    IdempotencyKey key =
        IdempotencyKey.derive(
            new IdempotencyScope(caller.tenant(), caller.principal(), operationName), clientKey);
    RequestFingerprint fingerprint =
        fingerprinter.fingerprint(
            new ZynqoInvocationContext(request, method, arguments, operationName));

    IdempotentResult<Object> result =
        engine.execute(
            key, fingerprint, operation.policy(), operation.codec(), invocation::proceed);
    if (result.replayed()) {
      HttpServletResponse response = servlet.getResponse();
      if (response != null) {
        response.setHeader(REPLAYED_HEADER, "true");
      }
    }
    return result.value();
  }

  /**
   * Returns the invoked method after verifying that it is the handler of the current request. Calls
   * of {@code @Zynqo} methods from other code (for example a service invoked by a controller) are
   * rejected instead of being silently treated as idempotent.
   */
  private static Method handlerMethod(MethodInvocation invocation, HttpServletRequest request) {
    Class<?> targetClass =
        invocation.getThis() == null ? null : AopUtils.getTargetClass(invocation.getThis());
    Method invoked =
        BridgeMethodResolver.findBridgedMethod(
            AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass));
    Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
    if (handler instanceof HandlerMethod handlerMethod
        && BridgeMethodResolver.findBridgedMethod(handlerMethod.getMethod()).equals(invoked)) {
      return invoked;
    }
    throw new IllegalStateException(
        "@Zynqo method "
            + invoked
            + " is not the Spring MVC handler of the current request; @Zynqo is supported only on"
            + " handler methods invoked by Spring MVC");
  }
}
