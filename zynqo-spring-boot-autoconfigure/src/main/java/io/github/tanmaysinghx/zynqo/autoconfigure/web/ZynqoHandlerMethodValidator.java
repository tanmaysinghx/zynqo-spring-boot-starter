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
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoConfigurationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Verifies every {@code @Zynqo} handler method at startup, so unsupported usage fails fast rather
 * than silently running without idempotency.
 *
 * <p>Checks that each method's configuration is valid (return type, parameters, key expression,
 * durations) and that its controller bean is actually advised by the Zynqo interceptor.
 */
final class ZynqoHandlerMethodValidator implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(ZynqoHandlerMethodValidator.class);

  private final ApplicationContext context;
  private final Advisor advisor;
  private final ZynqoInvocationHandler handler;

  ZynqoHandlerMethodValidator(
      ApplicationContext context, Advisor advisor, ZynqoInvocationHandler handler) {
    this.context = context;
    this.advisor = advisor;
    this.handler = handler;
  }

  @Override
  public void afterSingletonsInstantiated() {
    List<String> problems = new ArrayList<>();
    int validated = 0;
    for (RequestMappingHandlerMapping mapping :
        context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
          mapping.getHandlerMethods().entrySet()) {
        HandlerMethod handlerMethod = entry.getValue();
        if (!AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), Zynqo.class)) {
          continue;
        }
        validated++;
        String where = handlerMethod.getMethod().toGenericString();
        try {
          handler.metadata(handlerMethod.getMethod());
        } catch (IllegalArgumentException ex) {
          problems.add(where + ": " + ex.getMessage());
        }
        Object bean = handlerMethod.getBean();
        if (bean instanceof String name) {
          bean = context.getBean(name);
        }
        if (!(bean instanceof Advised advised)
            || !Arrays.asList(advised.getAdvisors()).contains(advisor)) {
          problems.add(
              where
                  + ": the controller bean is not proxied with the Zynqo interceptor (is the"
                  + " controller registered outside the application context?)");
        }
      }
    }
    if (!problems.isEmpty()) {
      throw new ZynqoConfigurationException(
          "Unsupported @Zynqo usage:\n  - " + String.join("\n  - ", problems),
          "Fix the listed handler methods. See docs/architecture.md#supported-handlers.");
    }
    log.info("Zynqo protects {} handler method(s)", validated);
  }
}
