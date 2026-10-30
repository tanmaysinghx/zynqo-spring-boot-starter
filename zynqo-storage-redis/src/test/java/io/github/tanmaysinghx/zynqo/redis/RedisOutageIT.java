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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyScope;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.ClaimResult;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import io.github.tanmaysinghx.zynqo.core.store.ZynqoStorageException;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;

/** Outage, timeout and restart behaviour against real Redis containers. */
class RedisOutageIT {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration TTL = Duration.ofHours(1);
  private static final RequestFingerprint FP =
      RequestFingerprint.builder().add("body", "a").build();
  private static final IdempotencyKey KEY =
      IdempotencyKey.derive(new IdempotencyScope("", "alice", "POST /orders"), "outage");

  @Test
  void unresponsiveRedisSurfacesAsStorageFailureWithinCommandTimeoutAndRecovers() {
    try (GenericContainer<?> redis = RedisTestSupport.redisContainer()) {
      redis.start();
      LettuceConnectionFactory factory = RedisTestSupport.connectionFactory(redis);
      try {
        RedisIdempotencyStore store = new RedisIdempotencyStore(factory, "zynqo-outage:");
        OwnershipToken token =
            ((ClaimResult.Acquired) store.claim(KEY, FP, "o1", LEASE, TTL)).token();

        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        long start = System.nanoTime();
        try {
          assertThatThrownBy(
                  () -> store.complete(KEY, token, new StoredResponse(200, Map.of(), null), TTL))
              .isInstanceOf(ZynqoStorageException.class)
              .hasMessageContaining("complete");
          assertThat(Duration.ofNanos(System.nanoTime() - start))
              .isLessThan(RedisTestSupport.COMMAND_TIMEOUT.multipliedBy(10));
        } finally {
          redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // The timed-out command may or may not have been applied; the store reports state as-is.
        await()
            .atMost(Duration.ofSeconds(10))
            .ignoreExceptionsInstanceOf(ZynqoStorageException.class)
            .until(() -> store.claim(KEY, FP, "o2", LEASE, TTL) != null);
      } finally {
        factory.destroy();
      }
    }
  }

  @Test
  void stoppedRedisSurfacesAsStorageFailure() {
    GenericContainer<?> redis = RedisTestSupport.redisContainer();
    redis.start();
    LettuceConnectionFactory factory = RedisTestSupport.connectionFactory(redis);
    try {
      RedisIdempotencyStore store = new RedisIdempotencyStore(factory, "zynqo-outage:");
      redis.stop();

      assertThatThrownBy(() -> store.claim(KEY, FP, "o1", LEASE, TTL))
          .isInstanceOf(ZynqoStorageException.class)
          .hasMessageContaining("claim");
    } finally {
      factory.destroy();
      redis.stop();
    }
  }

  @Test
  void stoppedConnectionFactorySurfacesAsStorageFailure() {
    try (GenericContainer<?> redis = RedisTestSupport.redisContainer()) {
      redis.start();
      LettuceConnectionFactory factory = RedisTestSupport.connectionFactory(redis);
      RedisIdempotencyStore store = new RedisIdempotencyStore(factory, "zynqo-outage:");
      factory.destroy();

      assertThatThrownBy(() -> store.release(KEY, new OwnershipToken("o1", 1)))
          .isInstanceOf(ZynqoStorageException.class)
          .hasMessageContaining("unexpectedly");
    }
  }

  @Test
  void gracefulRestartWithDefaultSnapshottingKeepsCompletedRecords() throws IOException {
    // The stock image saves an RDB snapshot on SIGTERM, so a graceful restart preserves records.
    ClaimResult afterRestart = completeThenRestart(RedisTestSupport.redisContainer());

    assertThat(afterRestart)
        .isEqualTo(new ClaimResult.Completed(new StoredResponse(201, Map.of(), new byte[] {1})));
  }

  @Test
  void restartWithoutPersistenceLosesRecordsAndKeyBecomesClaimableAgain() throws IOException {
    ClaimResult afterRestart =
        completeThenRestart(
            RedisTestSupport.redisContainer()
                .withCommand("redis-server", "--save", "", "--appendonly", "no"));

    // The completed record is gone: a retry with the same key would execute the operation again.
    assertThat(afterRestart)
        .isEqualTo(new ClaimResult.Acquired(new OwnershipToken("o2", 1), false));
  }

  private static ClaimResult completeThenRestart(GenericContainer<?> container) throws IOException {
    int hostPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      hostPort = socket.getLocalPort();
    }
    try (GenericContainer<?> redis =
        container.withCreateContainerCmdModifier(
            cmd ->
                cmd.getHostConfig()
                    .withPortBindings(
                        new PortBinding(
                            Ports.Binding.bindPort(hostPort), new ExposedPort(6379))))) {
      redis.start();
      LettuceConnectionFactory factory =
          RedisTestSupport.connectionFactory(redis.getHost(), hostPort);
      try {
        RedisIdempotencyStore store = new RedisIdempotencyStore(factory, "zynqo-outage:");
        OwnershipToken token =
            ((ClaimResult.Acquired) store.claim(KEY, FP, "o1", LEASE, TTL)).token();
        store.complete(KEY, token, new StoredResponse(201, Map.of(), new byte[] {1}), TTL);

        redis.getDockerClient().restartContainerCmd(redis.getContainerId()).exec();

        // The client reconnects automatically once Redis accepts connections again.
        return await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(100))
            .ignoreExceptionsInstanceOf(ZynqoStorageException.class)
            .until(() -> store.claim(KEY, FP, "o2", LEASE, TTL), r -> r != null);
      } finally {
        factory.destroy();
      }
    }
  }
}
