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

import io.github.tanmaysinghx.zynqo.core.ExceptionClassifier;
import io.github.tanmaysinghx.zynqo.core.IdempotencyEngine;
import io.github.tanmaysinghx.zynqo.core.ZynqoObserver;
import io.github.tanmaysinghx.zynqo.core.store.IdempotencyStore;
import io.github.tanmaysinghx.zynqo.redis.RedisIdempotencyStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Core Zynqo auto-configuration: properties, the idempotency engine and the Redis-backed store.
 *
 * <p>Every bean can be replaced by declaring a bean of the same type. Set {@code
 * zynqo.enabled=false} to disable Zynqo entirely.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnBooleanProperty(name = "zynqo.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ZynqoProperties.class)
public class ZynqoAutoConfiguration {

  /**
   * The exception classifier, from {@code zynqo.execution.on-exception}.
   *
   * @param properties the configuration
   * @return the classifier
   */
  @Bean
  @ConditionalOnMissingBean
  public ExceptionClassifier zynqoExceptionClassifier(ZynqoProperties properties) {
    return ExceptionClassifier.always(properties.getExecution().getOnException());
  }

  /**
   * The idempotency engine.
   *
   * @param properties the configuration
   * @param store the idempotency store
   * @param classifier the exception classifier
   * @param observer the observer, if any
   * @return the engine
   */
  @Bean
  @ConditionalOnMissingBean
  public IdempotencyEngine zynqoIdempotencyEngine(
      ZynqoProperties properties,
      ObjectProvider<IdempotencyStore> store,
      ExceptionClassifier classifier,
      ObjectProvider<ZynqoObserver> observer) {
    properties.validate();
    IdempotencyStore resolved = store.getIfAvailable();
    if (resolved == null) {
      throw new ZynqoConfigurationException(
          "Zynqo requires an IdempotencyStore but none is available.",
          "Add spring-boot-starter-data-redis and configure spring.data.redis.*, define an"
              + " IdempotencyStore bean, or set zynqo.enabled=false.");
    }
    return IdempotencyEngine.builder(resolved)
        .exceptionClassifier(classifier)
        .observer(observer.getIfAvailable(() -> ZynqoObserver.NOOP))
        .build();
  }

  /** Redis store, created from the application's {@link RedisConnectionFactory}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({RedisIdempotencyStore.class, RedisConnectionFactory.class})
  static class RedisStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    RedisIdempotencyStore zynqoRedisIdempotencyStore(
        RedisConnectionFactory connectionFactory, ZynqoProperties properties) {
      properties.validate();
      return new RedisIdempotencyStore(connectionFactory, properties.getStorage().getKeyPrefix());
    }
  }
}
