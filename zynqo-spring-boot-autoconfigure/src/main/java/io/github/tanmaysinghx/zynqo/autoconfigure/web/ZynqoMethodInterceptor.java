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

import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.util.function.SingletonSupplier;

/**
 * AOP interceptor for {@code @Zynqo} methods.
 *
 * <p>The invocation handler is resolved lazily on first use so that creating the advisor (which
 * happens early, while bean post-processors are registered) does not force early initialization of
 * the engine, the store or the Redis connection factory.
 */
final class ZynqoMethodInterceptor implements MethodInterceptor {

  private final SingletonSupplier<ZynqoInvocationHandler> handler;

  ZynqoMethodInterceptor(Supplier<ZynqoInvocationHandler> handler) {
    this.handler = SingletonSupplier.of(handler);
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    return handler.obtain().invoke(invocation);
  }
}
