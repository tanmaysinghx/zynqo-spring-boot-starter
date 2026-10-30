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
package io.github.tanmaysinghx.zynqo.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.tanmaysinghx.zynqo.autoconfigure.metrics.ZynqoMetricsAutoConfiguration;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.ZynqoCaller;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.ZynqoProblemDetailsAdvice;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.ZynqoRequestFingerprinter;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.ZynqoScopeResolver;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.ZynqoWebMvcAutoConfiguration;
import io.github.tanmaysinghx.zynqo.core.ExceptionClassifier;
import io.github.tanmaysinghx.zynqo.core.ExceptionDisposition;
import io.github.tanmaysinghx.zynqo.core.IdempotencyEngine;
import io.github.tanmaysinghx.zynqo.core.ZynqoObserver;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.IdempotencyStore;
import io.github.tanmaysinghx.zynqo.redis.RedisIdempotencyStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class ZynqoAutoConfigurationTest {

  private static final AutoConfigurations ZYNQO =
      AutoConfigurations.of(
          ZynqoMetricsAutoConfiguration.class,
          ZynqoAutoConfiguration.class,
          ZynqoWebMvcAutoConfiguration.class);

  private final WebApplicationContextRunner web =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  DispatcherServletAutoConfiguration.class,
                  WebMvcAutoConfiguration.class))
          .withConfiguration(ZYNQO);

  @Configuration(proxyBeanMethods = false)
  static class CustomStore {
    @Bean
    IdempotencyStore customStore() {
      return mock(IdempotencyStore.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RedisFactory {
    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      // Not started: creating the store must not open connections.
      return new LettuceConnectionFactory();
    }
  }

  @Test
  void autoConfiguresRedisStoreFromApplicationConnectionFactory() {
    web.withUserConfiguration(RedisFactory.class)
        .withPropertyValues("zynqo.storage.key-prefix=orders:")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RedisIdempotencyStore.class);
              assertThat(context).hasSingleBean(IdempotencyEngine.class);
              assertThat(context).hasSingleBean(ZynqoProblemDetailsAdvice.class);
              assertThat(context).hasBean(ZynqoWebMvcAutoConfiguration.ADVISOR_BEAN_NAME);
              assertThat(
                      context
                          .getBean(RedisIdempotencyStore.class)
                          .redisKey(
                              io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey.derive(
                                  new io.github.tanmaysinghx.zynqo.core.identity.IdempotencyScope(
                                      "", "", "op"),
                                  "k")))
                  .startsWith("orders:v1:");
            });
  }

  @Test
  void customStoreReplacesRedisStore() {
    web.withUserConfiguration(CustomStore.class, RedisFactory.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(IdempotencyStore.class);
              assertThat(context).doesNotHaveBean(RedisIdempotencyStore.class);
              assertThat(context).hasSingleBean(IdempotencyEngine.class);
            });
  }

  @Test
  void canBeDisabled() {
    web.withUserConfiguration(RedisFactory.class)
        .withPropertyValues("zynqo.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(IdempotencyEngine.class);
              assertThat(context).doesNotHaveBean(IdempotencyStore.class);
              assertThat(context).doesNotHaveBean(ZynqoWebMvcAutoConfiguration.ADVISOR_BEAN_NAME);
              assertThat(context).doesNotHaveBean(ZynqoProperties.class);
            });
  }

  @Test
  void failsWithActionableMessageWhenNoStoreIsAvailable() {
    web.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(ZynqoConfigurationException.class)
              .hasMessageContaining("requires an IdempotencyStore");
        });
  }

  @Test
  void failsOnInvalidProperties() {
    web.withUserConfiguration(CustomStore.class)
        .withPropertyValues(
            "zynqo.execution.lease-duration=0s",
            "zynqo.retention.completed-ttl=-1s",
            "zynqo.key.max-length=0",
            "zynqo.response.replay-headers=Location,Set-Cookie")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(ZynqoConfigurationException.class)
                  .hasMessageContaining("zynqo.execution.lease-duration")
                  .hasMessageContaining("zynqo.retention.completed-ttl")
                  .hasMessageContaining("zynqo.key.max-length")
                  .hasMessageContaining("Set-Cookie");
            });
  }

  @Test
  void startsWithoutRedisMicrometerOrWebOnTheClasspath() {
    new ApplicationContextRunner()
        .withClassLoader(
            new FilteredClassLoader(
                RedisConnectionFactory.class, RedisIdempotencyStore.class, MeterRegistry.class))
        .withConfiguration(ZYNQO)
        .withUserConfiguration(CustomStore.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(IdempotencyEngine.class);
              assertThat(context).doesNotHaveBean(ZynqoObserver.class);
              assertThat(context).doesNotHaveBean(Advisor.class);
            });
  }

  @Test
  void nonWebApplicationGetsEngineButNoWebIntegration() {
    new ApplicationContextRunner()
        .withConfiguration(ZYNQO)
        .withUserConfiguration(CustomStore.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(IdempotencyEngine.class);
              assertThat(context).doesNotHaveBean(ZynqoScopeResolver.class);
            });
  }

  @Test
  void registersMicrometerObserverWhenRegistryPresent() {
    web.withUserConfiguration(CustomStore.class)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ZynqoObserver.class);
              assertThat(context.getBean(ZynqoObserver.class).getClass().getSimpleName())
                  .isEqualTo("MicrometerZynqoObserver");
            });
  }

  @Test
  void exceptionDispositionComesFromProperties() {
    web.withUserConfiguration(CustomStore.class)
        .withPropertyValues("zynqo.execution.on-exception=mark-failed")
        .run(
            context ->
                assertThat(
                        context.getBean(ExceptionClassifier.class).classify(new RuntimeException()))
                    .isEqualTo(ExceptionDisposition.MARK_FAILED));
  }

  @Test
  void consumerBeansOverrideDefaults() {
    ZynqoScopeResolver scope = request -> new ZynqoCaller("t", "p");
    ZynqoRequestFingerprinter fingerprinter =
        invocation -> RequestFingerprint.builder().add("x", "y").build();
    ExceptionClassifier classifier = failure -> ExceptionDisposition.MARK_FAILED;
    ZynqoObserver observer = new ZynqoObserver() {};
    web.withUserConfiguration(CustomStore.class)
        .withBean(ZynqoScopeResolver.class, () -> scope)
        .withBean(ZynqoRequestFingerprinter.class, () -> fingerprinter)
        .withBean(ExceptionClassifier.class, () -> classifier)
        .withBean(ZynqoObserver.class, () -> observer)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context -> {
              assertThat(context.getBean(ZynqoScopeResolver.class)).isSameAs(scope);
              assertThat(context.getBean(ZynqoRequestFingerprinter.class)).isSameAs(fingerprinter);
              assertThat(context.getBean(ExceptionClassifier.class)).isSameAs(classifier);
              assertThat(context.getBean(ZynqoObserver.class)).isSameAs(observer);
            });
  }

  @Test
  void interceptorOrderIsConfigurable() {
    web.withUserConfiguration(CustomStore.class)
        .withPropertyValues("zynqo.interceptor-order=42")
        .run(
            context ->
                assertThat(
                        ((org.springframework.core.Ordered)
                                context.getBean(ZynqoWebMvcAutoConfiguration.ADVISOR_BEAN_NAME))
                            .getOrder())
                    .isEqualTo(42));
  }

  @Test
  void configurationMetadataIsGenerated() throws Exception {
    try (var in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
      assertThat(in).isNotNull();
      String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(json)
          .contains("zynqo.execution.lease-duration")
          .contains("zynqo.retention.completed-ttl")
          .contains("zynqo.key.header-name")
          .contains("zynqo.scope.require-principal");
    }
  }

  @Test
  void failureAnalyzerReportsAction() {
    ZynqoConfigurationException ex = new ZynqoConfigurationException("broken", "fix it");
    var analysis = new ZynqoConfigurationFailureAnalyzer().analyze(new RuntimeException(ex));

    assertThat(analysis.getDescription()).isEqualTo("broken");
    assertThat(analysis.getAction()).isEqualTo("fix it");
  }
}
