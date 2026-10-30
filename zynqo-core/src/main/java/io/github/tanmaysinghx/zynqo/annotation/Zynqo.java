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
package io.github.tanmaysinghx.zynqo.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a Spring MVC handler method is idempotent: repeating a request with the same
 * idempotency key and the same payload replays the first outcome instead of executing the method
 * again.
 *
 * <p>The idempotency key is taken from the {@code Idempotency-Key} HTTP request header (header name
 * configurable through {@code zynqo.key.header-name}) unless {@link #key()} supplies a SpEL
 * expression evaluated against the method arguments.
 *
 * <p>The key a client supplies is never used on its own. It is always combined with the tenant, the
 * authenticated principal and the operation (see {@link #operation()}), so one caller can never
 * observe another caller's stored response.
 *
 * <p>Interception is implemented with Spring AOP. Consequently:
 *
 * <ul>
 *   <li>the method must be a public or protected, non-final, non-static method of a Spring
 *       {@code @Controller} bean that is invoked by Spring MVC; this is verified at startup;
 *   <li>self-invocation ({@code this.method()}) is not intercepted;
 *   <li>the advice runs inside method-security interceptors and outside {@code @Transactional}
 *       advice, so authorization is re-evaluated for every request and completion is recorded only
 *       after a transaction commits.
 * </ul>
 *
 * <p>Durations accept ISO-8601 ({@code PT10M}) and simple ({@code 10m}, {@code 30s}) formats. An
 * empty value falls back to the global configuration.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Zynqo {

  /**
   * SpEL expression evaluated against the method arguments that yields the client idempotency key,
   * for example {@code #request.orderId}. Arguments are available by name ({@code #name}) and by
   * index ({@code #p0}, {@code #a0}). When empty, the key is read from the idempotency key request
   * header.
   *
   * @return the key expression, or an empty string to use the request header
   */
  String key() default "";

  /**
   * Stable name of the protected operation. It is part of the key scope and must change only when
   * the meaning of the operation changes. Defaults to the HTTP method and the matched route
   * pattern, for example {@code POST /orders}.
   *
   * @return the operation name, or an empty string for the default
   */
  String operation() default "";

  /**
   * How long a completed response is retained and replayed. Overrides {@code
   * zynqo.retention.completed-ttl}.
   *
   * @return the retention duration, or an empty string for the global default
   */
  String retention() default "";

  /**
   * How long a claim stays exclusively owned while the operation executes. Overrides {@code
   * zynqo.execution.lease-duration}. It must exceed the worst-case execution time of the method.
   *
   * @return the lease duration, or an empty string for the global default
   */
  String lease() default "";
}
