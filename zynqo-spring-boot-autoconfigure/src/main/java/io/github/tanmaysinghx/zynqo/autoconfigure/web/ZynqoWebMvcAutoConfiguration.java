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
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoAutoConfiguration;
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoProperties;
import io.github.tanmaysinghx.zynqo.core.IdempotencyEngine;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring MVC integration: intercepts {@link Zynqo @Zynqo} handler methods, renders errors as
 * Problem Details and validates every annotated handler at startup.
 *
 * <p>Only servlet-based Spring MVC applications are supported. Reactive (WebFlux) applications do
 * not activate this configuration.
 */
@AutoConfiguration(
    after = ZynqoAutoConfiguration.class,
    afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnBooleanProperty(name = "zynqo.enabled", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, JsonMapper.class})
@ConditionalOnBean(IdempotencyEngine.class)
@Import(ZynqoAutoProxyRegistrar.class)
public class ZynqoWebMvcAutoConfiguration {

  /** Name of the Zynqo advisor bean. */
  public static final String ADVISOR_BEAN_NAME = "zynqoAdvisor";

  /**
   * The advisor applying the Zynqo interceptor to {@code @Zynqo} methods. It is ordered by {@code
   * zynqo.interceptor-order} (default {@value ZynqoProperties#DEFAULT_INTERCEPTOR_ORDER}): inside
   * Spring Security method interceptors, outside transaction advice.
   *
   * @param environment the environment, read directly because advisors are created early
   * @param handler the invocation handler, resolved lazily on first invocation
   * @return the advisor
   */
  @Bean(ADVISOR_BEAN_NAME)
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor zynqoAdvisor(
      Environment environment, ObjectProvider<ZynqoInvocationHandler> handler) {
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Zynqo.class, true),
            new ZynqoMethodInterceptor(handler::getObject));
    advisor.setOrder(
        Binder.get(environment)
            .bind("zynqo.interceptor-order", Integer.class)
            .orElse(ZynqoProperties.DEFAULT_INTERCEPTOR_ORDER));
    return advisor;
  }

  @Bean
  @ConditionalOnMissingBean
  ZynqoScopeResolver zynqoScopeResolver(ZynqoProperties properties) {
    return new DefaultZynqoScopeResolver(properties.getScope().getTenantHeader());
  }

  @Bean
  @ConditionalOnMissingBean
  ZynqoRequestFingerprinter zynqoRequestFingerprinter(ObjectProvider<JsonMapper> mapper) {
    return new JacksonRequestFingerprinter(mapper.getIfAvailable(JsonMapper::shared));
  }

  @Bean
  ZynqoInvocationHandler zynqoInvocationHandler(
      IdempotencyEngine engine,
      ZynqoProperties properties,
      ObjectProvider<JsonMapper> mapper,
      ZynqoScopeResolver scopeResolver,
      ZynqoRequestFingerprinter fingerprinter) {
    return new ZynqoInvocationHandler(
        engine,
        properties,
        mapper.getIfAvailable(JsonMapper::shared),
        scopeResolver,
        fingerprinter);
  }

  @Bean
  @ConditionalOnMissingBean
  ZynqoProblemDetailsAdvice zynqoProblemDetailsAdvice() {
    return new ZynqoProblemDetailsAdvice();
  }

  @Bean
  ZynqoHandlerMethodValidator zynqoHandlerMethodValidator(
      ApplicationContext context,
      @Qualifier(ADVISOR_BEAN_NAME) Advisor advisor,
      ZynqoInvocationHandler handler) {
    return new ZynqoHandlerMethodValidator(context, advisor, handler);
  }
}
