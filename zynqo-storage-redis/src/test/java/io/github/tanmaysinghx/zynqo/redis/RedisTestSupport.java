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
package io.github.tanmaysinghx.zynqo.redis;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared Redis Testcontainers configuration. */
final class RedisTestSupport {

  /** Redis version the store is verified against. */
  static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");

  /** Command timeout used by tests so that outages surface quickly and deterministically. */
  static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);

  private RedisTestSupport() {}

  @SuppressWarnings("resource")
  static GenericContainer<?> redisContainer() {
    return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
  }

  /** Creates and starts a connection factory, like one application instance would. */
  static LettuceConnectionFactory connectionFactory(String host, int port) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(host, port),
            LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build());
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  static LettuceConnectionFactory connectionFactory(GenericContainer<?> redis) {
    return connectionFactory(redis.getHost(), redis.getMappedPort(6379));
  }
}
