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
package io.github.tanmaysinghx.zynqo.autoconfigure.metrics;

import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoAutoConfiguration;
import io.github.tanmaysinghx.zynqo.core.ZynqoObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Publishes Zynqo metrics to Micrometer when a {@link MeterRegistry} is available. */
@AutoConfiguration(
    before = ZynqoAutoConfiguration.class,
    afterName =
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnBooleanProperty(name = "zynqo.enabled", matchIfMissing = true)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class ZynqoMetricsAutoConfiguration {

  /**
   * The Micrometer observer.
   *
   * @param registry the meter registry
   * @return the observer
   */
  @Bean
  @ConditionalOnMissingBean(ZynqoObserver.class)
  public ZynqoObserver zynqoMicrometerObserver(MeterRegistry registry) {
    return new MicrometerZynqoObserver(registry);
  }
}
