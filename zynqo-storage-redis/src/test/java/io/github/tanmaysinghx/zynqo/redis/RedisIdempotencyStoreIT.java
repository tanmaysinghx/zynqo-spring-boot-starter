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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyScope;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.ClaimResult;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import io.github.tanmaysinghx.zynqo.core.store.ZynqoStorageException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisIdempotencyStoreIT {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration TTL = Duration.ofHours(1);
  private static final RequestFingerprint FP =
      RequestFingerprint.builder().add("body", "a").build();
  private static final RequestFingerprint OTHER_FP =
      RequestFingerprint.builder().add("body", "b").build();

  @Container private static final GenericContainer<?> REDIS = RedisTestSupport.redisContainer();

  private static LettuceConnectionFactory factory;
  private static RedisIdempotencyStore store;
  private static StringRedisTemplate redis;

  private IdempotencyKey key;

  @BeforeAll
  static void connect() {
    factory = RedisTestSupport.connectionFactory(REDIS);
    store = new RedisIdempotencyStore(factory, "zynqo-test:");
    redis = new StringRedisTemplate(factory);
  }

  @AfterAll
  static void disconnect() {
    factory.destroy();
  }

  @BeforeEach
  void newKey() {
    key =
        IdempotencyKey.derive(
            new IdempotencyScope("tenant", "alice", "POST /orders"), UUID.randomUUID().toString());
  }

  private String redisKey() {
    return store.redisKey(key);
  }

  private OwnershipToken acquire(String owner) {
    ClaimResult result = store.claim(key, FP, owner, LEASE, TTL);
    assertThat(result).isInstanceOf(ClaimResult.Acquired.class);
    return ((ClaimResult.Acquired) result).token();
  }

  /** Moves the lease into the past on the server, as if the lease duration had elapsed. */
  private void expireLease() {
    redis.opsForHash().put(redisKey(), "lease_until", "0");
  }

  @Test
  void keyIsNamespacedAndVersioned() {
    acquire("o1");

    assertThat(redisKey()).isEqualTo("zynqo-test:v1:" + key.id());
    assertThat(redis.hasKey(redisKey())).isTrue();
  }

  @Test
  void firstClaimAcquiresWithFenceOne() {
    ClaimResult result = store.claim(key, FP, "o1", LEASE, TTL);

    assertThat(result).isEqualTo(new ClaimResult.Acquired(new OwnershipToken("o1", 1), false));
    assertThat(redis.opsForHash().get(redisKey(), "state")).isEqualTo("IN_PROGRESS");
    assertThat(redis.opsForHash().get(redisKey(), "fp")).isEqualTo(FP.value());
  }

  @Test
  void rawClientKeyAndPayloadAreNeverStored() {
    OwnershipToken token = acquire("o1");
    store.complete(key, token, new StoredResponse(200, Map.of(), new byte[] {1}), TTL);

    Map<Object, Object> record = redis.opsForHash().entries(redisKey());
    assertThat(record.keySet())
        .containsExactlyInAnyOrder("state", "fp", "fence", "status", "headers", "body");
    assertThat(record.values().toString()).doesNotContain("alice").doesNotContain("tenant");
  }

  @Test
  void duplicateDuringLeaseIsInProgressWithRemainingLease() {
    acquire("o1");

    ClaimResult result = store.claim(key, FP, "o2", LEASE, TTL);

    assertThat(result)
        .isInstanceOfSatisfying(
            ClaimResult.InProgress.class,
            inProgress ->
                assertThat(inProgress.leaseRemaining()).isPositive().isLessThanOrEqualTo(LEASE));
  }

  @Test
  void differentFingerprintIsRejectedInEveryState() {
    OwnershipToken token = acquire("o1");
    assertThat(store.claim(key, OTHER_FP, "o2", LEASE, TTL))
        .isInstanceOf(ClaimResult.FingerprintMismatch.class);

    store.complete(key, token, new StoredResponse(201, Map.of(), null), TTL);
    assertThat(store.claim(key, OTHER_FP, "o3", LEASE, TTL))
        .isInstanceOf(ClaimResult.FingerprintMismatch.class);
  }

  @Test
  void completedRecordReplaysStatusHeadersAndBinaryBody() {
    OwnershipToken token = acquire("o1");
    byte[] body = new byte[256];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) i;
    }
    StoredResponse response =
        new StoredResponse(
            201,
            Map.of("Location", List.of("/orders/1"), "Content-Language", List.of("en", "de")),
            body);

    assertThat(store.complete(key, token, response, TTL)).isTrue();

    assertThat(store.claim(key, FP, "o2", LEASE, TTL))
        .isEqualTo(new ClaimResult.Completed(response));
    assertThat(redis.opsForHash().hasKey(redisKey(), "owner")).isFalse();
    assertThat(redis.opsForHash().hasKey(redisKey(), "lease_until")).isFalse();
  }

  @Test
  void completedRecordDistinguishesMissingAndEmptyBody() {
    OwnershipToken token = acquire("o1");
    store.complete(key, token, new StoredResponse(0, Map.of(), null), TTL);
    assertThat(store.claim(key, FP, "o2", LEASE, TTL))
        .isEqualTo(new ClaimResult.Completed(new StoredResponse(0, Map.of(), null)));

    newKey();
    OwnershipToken second = acquire("o1");
    store.complete(key, second, new StoredResponse(200, Map.of(), new byte[0]), TTL);
    assertThat(store.claim(key, FP, "o2", LEASE, TTL))
        .isEqualTo(new ClaimResult.Completed(new StoredResponse(200, Map.of(), new byte[0])));
  }

  @Test
  void completionSetsRetentionTtl() {
    OwnershipToken token = acquire("o1");
    Duration retention = Duration.ofMinutes(10);

    store.complete(key, token, new StoredResponse(200, Map.of(), null), retention);

    Long pttl = redis.getExpire(redisKey(), TimeUnit.MILLISECONDS);
    assertThat(pttl).isPositive().isLessThanOrEqualTo(retention.toMillis());
    assertThat(pttl).isGreaterThan(retention.minusMinutes(1).toMillis());
  }

  @Test
  void inProgressRecordUsesRecordTtl() {
    store.claim(key, FP, "o1", LEASE, Duration.ofMinutes(5));

    assertThat(redis.getExpire(redisKey(), TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofMinutes(5).toMillis());
  }

  @Test
  void recordExpiresAfterRetentionAndKeyBecomesClaimableAgain() {
    OwnershipToken token = acquire("o1");
    store.complete(key, token, new StoredResponse(200, Map.of(), null), Duration.ofMillis(200));

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !Boolean.TRUE.equals(redis.hasKey(redisKey())));

    assertThat(store.claim(key, FP, "o2", LEASE, TTL))
        .isEqualTo(new ClaimResult.Acquired(new OwnershipToken("o2", 1), false));
  }

  @Test
  void expiredLeaseIsTakenOverWithHigherFence() {
    acquire("o1");
    expireLease();

    ClaimResult result = store.claim(key, FP, "o2", LEASE, TTL);

    assertThat(result).isEqualTo(new ClaimResult.Acquired(new OwnershipToken("o2", 2), true));
    assertThat(redis.opsForHash().get(redisKey(), "owner")).isEqualTo("o2");
  }

  @Test
  void leaseExpiresInRealTimeOnTheServerClock() {
    store.claim(key, FP, "o1", Duration.ofMillis(300), TTL);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () ->
                store.claim(key, FP, "o2", Duration.ofSeconds(30), TTL)
                    instanceof ClaimResult.Acquired);

    assertThat(redis.opsForHash().get(redisKey(), "fence")).isEqualTo("2");
  }

  @Test
  void staleOwnerCannotCompleteReleaseOrFailAfterTakeover() {
    OwnershipToken stale = acquire("o1");
    expireLease();
    OwnershipToken current =
        ((ClaimResult.Acquired) store.claim(key, FP, "o2", LEASE, TTL)).token();

    assertThat(store.complete(key, stale, new StoredResponse(200, Map.of(), new byte[] {1}), TTL))
        .isFalse();
    assertThat(store.release(key, stale)).isFalse();
    assertThat(store.fail(key, stale, TTL)).isFalse();
    assertThat(redis.opsForHash().get(redisKey(), "state")).isEqualTo("IN_PROGRESS");

    StoredResponse winner = new StoredResponse(201, Map.of(), new byte[] {2});
    assertThat(store.complete(key, current, winner, TTL)).isTrue();
    assertThat(store.claim(key, FP, "o3", LEASE, TTL)).isEqualTo(new ClaimResult.Completed(winner));
  }

  @Test
  void ownerIdAloneIsNotEnoughWithoutMatchingFence() {
    OwnershipToken token = acquire("o1");

    assertThat(
            store.complete(
                key,
                new OwnershipToken("o1", token.fence() + 1),
                new StoredResponse(200, Map.of(), null),
                TTL))
        .isFalse();
    assertThat(store.release(key, new OwnershipToken("o2", token.fence()))).isFalse();
  }

  @Test
  void transitionsOnMissingRecordAreRejected() {
    OwnershipToken token = new OwnershipToken("o1", 1);

    assertThat(store.complete(key, token, new StoredResponse(200, Map.of(), null), TTL)).isFalse();
    assertThat(store.release(key, token)).isFalse();
    assertThat(store.fail(key, token, TTL)).isFalse();
    assertThat(redis.hasKey(redisKey())).isFalse();
  }

  @Test
  void completedRecordCannotBeCompletedAgain() {
    OwnershipToken token = acquire("o1");
    StoredResponse first = new StoredResponse(200, Map.of(), new byte[] {1});
    store.complete(key, token, first, TTL);

    assertThat(store.complete(key, token, new StoredResponse(200, Map.of(), new byte[] {2}), TTL))
        .isFalse();
    assertThat(store.release(key, token)).isFalse();
    assertThat(store.claim(key, FP, "o2", LEASE, TTL)).isEqualTo(new ClaimResult.Completed(first));
  }

  @Test
  void releaseDeletesOwnedRecord() {
    OwnershipToken token = acquire("o1");

    assertThat(store.release(key, token)).isTrue();

    assertThat(redis.hasKey(redisKey())).isFalse();
    assertThat(store.claim(key, FP, "o2", LEASE, TTL))
        .isEqualTo(new ClaimResult.Acquired(new OwnershipToken("o2", 1), false));
  }

  @Test
  void failedRecordBlocksReexecutionForRetention() {
    OwnershipToken token = acquire("o1");

    assertThat(store.fail(key, token, Duration.ofMinutes(3))).isTrue();

    assertThat(store.claim(key, FP, "o2", LEASE, TTL)).isInstanceOf(ClaimResult.Failed.class);
    assertThat(redis.getExpire(redisKey(), TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofMinutes(3).toMillis());
  }

  @Test
  void corruptRecordIsReportedAsStorageFailure() {
    redis.opsForHash().putAll(redisKey(), Map.of("state", "BOGUS", "fp", FP.value()));

    assertThatThrownBy(() -> store.claim(key, FP, "o1", LEASE, TTL))
        .isInstanceOf(ZynqoStorageException.class);
  }

  @Test
  void inProgressRecordWithoutLeaseIsReportedAsStorageFailure() {
    redis.opsForHash().putAll(redisKey(), Map.of("state", "IN_PROGRESS", "fp", FP.value()));

    assertThatThrownBy(() -> store.claim(key, FP, "o1", LEASE, TTL))
        .isInstanceOf(ZynqoStorageException.class);
  }

  @Test
  void completedRecordWithoutStatusIsReportedAsStorageFailure() {
    redis.opsForHash().putAll(redisKey(), Map.of("state", "COMPLETED", "fp", FP.value()));

    assertThatThrownBy(() -> store.claim(key, FP, "o1", LEASE, TTL))
        .isInstanceOf(ZynqoStorageException.class);
  }

  @Test
  void rejectsInvalidConfigurationAndDurations() {
    assertThatIllegalArgumentException().isThrownBy(() -> new RedisIdempotencyStore(factory, " "));
    assertThatThrownBy(() -> new RedisIdempotencyStore(null, "p"))
        .isInstanceOf(NullPointerException.class);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.claim(key, FP, "o1", Duration.ofNanos(10), TTL));
  }

  @Test
  void onlyOneOfManyConcurrentClaimantsAcrossInstancesAcquires() throws Exception {
    int instances = 4;
    int claimantsPerInstance = 75;
    List<LettuceConnectionFactory> factories = new ArrayList<>();
    List<RedisIdempotencyStore> stores = new ArrayList<>();
    for (int i = 0; i < instances; i++) {
      LettuceConnectionFactory instanceFactory = RedisTestSupport.connectionFactory(REDIS);
      factories.add(instanceFactory);
      stores.add(new RedisIdempotencyStore(instanceFactory, "zynqo-test:"));
    }
    CountDownLatch start = new CountDownLatch(1);
    List<ClaimResult> results = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(64);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < instances * claimantsPerInstance; i++) {
        RedisIdempotencyStore instanceStore = stores.get(i % instances);
        String owner = "owner-" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  results.add(instanceStore.claim(key, FP, owner, LEASE, TTL));
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      factories.forEach(LettuceConnectionFactory::destroy);
    }

    assertThat(results).hasSize(instances * claimantsPerInstance);
    assertThat(results).filteredOn(ClaimResult.Acquired.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(ClaimResult.InProgress.class::isInstance)
        .hasSize(instances * claimantsPerInstance - 1);
  }

  @Test
  void concurrentTakeoverOfExpiredLeaseAdmitsExactlyOneNewOwner() throws Exception {
    acquire("crashed-owner");
    expireLease();
    int claimants = 200;
    CountDownLatch start = new CountDownLatch(1);
    List<ClaimResult> results = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(50);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < claimants; i++) {
        String owner = "recoverer-" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  results.add(store.claim(key, FP, owner, LEASE, TTL));
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(results)
        .filteredOn(ClaimResult.Acquired.class::isInstance)
        .singleElement()
        .satisfies(
            r -> {
              ClaimResult.Acquired acquired = (ClaimResult.Acquired) r;
              assertThat(acquired.recovered()).isTrue();
              assertThat(acquired.token().fence()).isEqualTo(2);
            });
    assertThat(redis.opsForHash().get(redisKey(), "fence")).isEqualTo("2");
  }
}
