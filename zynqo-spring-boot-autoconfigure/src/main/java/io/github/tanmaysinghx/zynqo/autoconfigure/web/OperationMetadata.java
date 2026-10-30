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

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoProperties;
import io.github.tanmaysinghx.zynqo.core.ExecutionPolicy;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validated, immutable description of one {@code @Zynqo} handler method. Created once per method
 * and reused for every request.
 */
final class OperationMetadata {

  private static final ExpressionParser PARSER = new SpelExpressionParser();

  private final Method method;
  private final Expression keyExpression;
  private final String keyHeader;
  private final String operation;
  private final String[] parameterNames;
  private final ExecutionPolicy policy;
  private final JacksonResponseCodec codec;

  private OperationMetadata(
      Method method,
      Expression keyExpression,
      String keyHeader,
      String operation,
      String[] parameterNames,
      ExecutionPolicy policy,
      JacksonResponseCodec codec) {
    this.method = method;
    this.keyExpression = keyExpression;
    this.keyHeader = keyHeader;
    this.operation = operation;
    this.parameterNames = parameterNames;
    this.policy = policy;
    this.codec = codec;
  }

  /**
   * Builds and validates the metadata of a handler method.
   *
   * @throws IllegalArgumentException listing every problem with the method
   */
  static OperationMetadata create(Method method, ZynqoProperties properties, JsonMapper mapper) {
    Zynqo zynqo = AnnotatedElementUtils.findMergedAnnotation(method, Zynqo.class);
    if (zynqo == null) {
      throw new IllegalArgumentException("method is not annotated with @Zynqo");
    }
    List<String> problems = new ArrayList<>();

    int modifiers = method.getModifiers();
    if (Modifier.isPrivate(modifiers)
        || Modifier.isStatic(modifiers)
        || Modifier.isFinal(modifiers)) {
      problems.add("method must not be private, static or final (it cannot be intercepted)");
    }
    if (Modifier.isFinal(method.getDeclaringClass().getModifiers())) {
      problems.add("declaring class must not be final (it cannot be proxied)");
    }

    boolean responseBody =
        AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
            || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), ResponseBody.class)
            || ResponseEntity.class.isAssignableFrom(method.getReturnType());
    if (!responseBody) {
      problems.add(
          "method must be a @ResponseBody / @RestController method or return ResponseEntity");
    }

    JacksonResponseCodec codec = null;
    try {
      codec =
          JacksonResponseCodec.forMethod(
              method, mapper, properties.getResponse().getReplayHeaders());
    } catch (IllegalArgumentException ex) {
      problems.add(ex.getMessage());
    }

    for (int i = 0; i < method.getParameterCount(); i++) {
      String reason = HandlerParameters.unsupportedReason(new MethodParameter(method, i));
      if (reason != null) {
        problems.add("parameter " + i + ": " + reason);
      }
    }

    String[] names = HandlerParameters.names(method);
    Expression expression = null;
    if (!zynqo.key().isBlank()) {
      try {
        expression = PARSER.parseExpression(zynqo.key());
      } catch (ParseException ex) {
        problems.add("key expression '" + zynqo.key() + "' is invalid: " + ex.getMessage());
      }
      if (names == null && !zynqo.key().matches("^#[ap]\\d+.*")) {
        problems.add(
            "parameter names are unavailable; compile with -parameters or reference arguments as"
                + " #p0, #p1, ...");
      }
    }

    ZynqoProperties.Execution execution = properties.getExecution();
    Duration lease = duration(zynqo.lease(), "lease", execution.getLeaseDuration(), problems);
    Duration retention =
        duration(
            zynqo.retention(), "retention", properties.getRetention().getCompletedTtl(), problems);

    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", problems));
    }
    ExecutionPolicy policy =
        new ExecutionPolicy(
            lease,
            retention,
            properties.getRetention().getFailedTtl(),
            execution.getWaitTimeout(),
            execution.getPollInterval(),
            (int) properties.getResponse().getMaxBodySize().toBytes());
    return new OperationMetadata(
        method,
        expression,
        properties.getKey().getHeaderName(),
        zynqo.operation().trim(),
        names,
        policy,
        codec);
  }

  private static Duration duration(
      String value, String attribute, Duration fallback, List<String> problems) {
    if (value.isBlank()) {
      return fallback;
    }
    try {
      Duration parsed = DurationStyle.detectAndParse(value.trim());
      if (parsed.isNegative() || parsed.isZero()) {
        problems.add(attribute + " must be positive");
        return fallback;
      }
      return parsed;
    } catch (IllegalArgumentException ex) {
      problems.add(attribute + " '" + value + "' is not a valid duration");
      return fallback;
    }
  }

  Method method() {
    return method;
  }

  ExecutionPolicy policy() {
    return policy;
  }

  JacksonResponseCodec codec() {
    return codec;
  }

  /** Returns the operation name: the explicit name, or {@code METHOD /matched/pattern}. */
  String operation(HttpServletRequest request) {
    if (!operation.isEmpty()) {
      return operation;
    }
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String route =
        pattern == null
            ? method.getDeclaringClass().getName() + "#" + method.getName()
            : pattern.toString();
    return request.getMethod() + " " + route;
  }

  /**
   * Resolves the raw client key from the SpEL expression or the request header.
   *
   * @return the key, or {@code null} if absent
   * @throws ZynqoException if the header is repeated
   */
  String clientKey(Object[] arguments, HttpServletRequest request) {
    if (keyExpression == null) {
      Enumeration<String> values = request.getHeaders(keyHeader);
      List<String> all = values == null ? List.of() : Collections.list(values);
      if (all.size() > 1) {
        throw new ZynqoException(
            ZynqoErrorCode.KEY_INVALID, "The idempotency key header must appear only once.");
      }
      return all.isEmpty() ? null : all.get(0);
    }
    Object value = keyExpression.getValue(evaluationContext(arguments));
    if (value == null) {
      return null;
    }
    if (value instanceof CharSequence || value instanceof Number || value instanceof UUID) {
      return value.toString();
    }
    throw new IllegalStateException(
        "@Zynqo key expression on "
            + method
            + " must evaluate to a String, Number or UUID but returned "
            + value.getClass().getName());
  }

  private EvaluationContext evaluationContext(Object[] arguments) {
    // Read-only data binding: no type references, constructors, bean references or assignment.
    SimpleEvaluationContext context =
        SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
    for (int i = 0; i < arguments.length; i++) {
      context.setVariable("p" + i, arguments[i]);
      context.setVariable("a" + i, arguments[i]);
      if (parameterNames != null) {
        context.setVariable(parameterNames[i], arguments[i]);
      }
    }
    return context;
  }
}
