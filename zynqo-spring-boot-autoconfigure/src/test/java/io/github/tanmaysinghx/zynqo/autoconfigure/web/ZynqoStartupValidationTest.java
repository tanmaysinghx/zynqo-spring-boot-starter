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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoAutoConfiguration;
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoConfigurationException;
import io.github.tanmaysinghx.zynqo.core.store.IdempotencyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.method.AuthorizationAdvisor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class ZynqoStartupValidationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AopAutoConfiguration.class,
                  JacksonAutoConfiguration.class,
                  DispatcherServletAutoConfiguration.class,
                  WebMvcAutoConfiguration.class,
                  ZynqoAutoConfiguration.class,
                  ZynqoWebMvcAutoConfiguration.class))
          .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class));

  @RestController
  static class GoodController {
    @Zynqo
    @PostMapping("/good")
    public String good() {
      return "ok";
    }
  }

  @RestController
  static class BadController {
    @Zynqo
    @PostMapping("/async")
    public CompletableFuture<String> async() {
      return CompletableFuture.completedFuture("x");
    }

    @Zynqo(lease = "never")
    @PostMapping("/lease")
    public String lease() {
      return "x";
    }
  }

  @RestController
  static class SecuredTransactionalController {
    @Zynqo
    @PreAuthorize("hasRole('USER')")
    @Transactional
    @PostMapping("/secured")
    public String secured() {
      return "ok";
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  @EnableTransactionManagement
  static class SecurityAndTransactions {
    @Bean
    PlatformTransactionManager transactionManager() {
      return mock(PlatformTransactionManager.class);
    }
  }

  @Test
  void validHandlersStartAndAreProxied() {
    runner
        .withBean(GoodController.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(AopUtils.isAopProxy(context.getBean(GoodController.class))).isTrue();
            });
  }

  @Test
  void invalidHandlersFailStartupListingEveryProblem() {
    runner
        .withBean(BadController.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .isInstanceOf(ZynqoConfigurationException.class)
                  .hasMessageContaining("BadController.async()")
                  .hasMessageContaining("CompletableFuture")
                  .hasMessageContaining("BadController.lease()")
                  .hasMessageContaining("lease 'never'");
            });
  }

  @Test
  void proxiesHandlersEvenWhenBootAopAutoConfigurationIsDisabled() {
    runner
        .withPropertyValues("spring.aop.auto=false")
        .withBean(GoodController.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(AopUtils.isAopProxy(context.getBean(GoodController.class))).isTrue();
            });
  }

  @Test
  void interceptorRunsInsideMethodSecurityAndOutsideTransactions() {
    runner
        .withUserConfiguration(SecurityAndTransactions.class)
        .withBean(SecuredTransactionalController.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Advised advised = (Advised) context.getBean(SecuredTransactionalController.class);
              List<Advisor> chain = Arrays.asList(advised.getAdvisors());
              Object zynqoAdvisor = context.getBean(ZynqoWebMvcAutoConfiguration.ADVISOR_BEAN_NAME);

              int security =
                  indexOf(
                      chain,
                      advisor ->
                          advisor instanceof AuthorizationAdvisor
                              || advisor.getAdvice() instanceof AuthorizationAdvisor);
              int zynqo = indexOf(chain, advisor -> advisor == zynqoAdvisor);
              int transaction =
                  indexOf(chain, advisor -> advisor.getAdvice() instanceof TransactionInterceptor);
              assertThat(security).as("method security advisor in %s", chain).isNotNegative();
              assertThat(zynqo).isGreaterThan(security);
              assertThat(transaction).isGreaterThan(zynqo);
            });
  }

  private static int indexOf(List<Advisor> chain, Predicate<Advisor> predicate) {
    for (int i = 0; i < chain.size(); i++) {
      if (predicate.test(chain.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
