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

import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.ClaimResult;
import io.github.tanmaysinghx.zynqo.core.store.IdempotencyStore;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import io.github.tanmaysinghx.zynqo.core.store.ZynqoStorageException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * {@link IdempotencyStore} backed by Redis.
 *
 * <p>Each record is a Redis hash at {@code <prefix>v1:<sha256>}. Every state transition is a single
 * Lua script, so it is atomic with respect to all clients, including other application instances.
 * Transitions after a claim are accepted only when both the owner id and the fence match, so a
 * worker whose lease was taken over can never complete, release or fail the newer owner's record.
 *
 * <p>Design properties:
 *
 * <ul>
 *   <li>one key per record and one key per script call, so the store works with Redis Cluster;
 *   <li>lease expiry uses the Redis server clock ({@code TIME}); application clock skew is
 *       irrelevant;
 *   <li>only digests, status codes, allow-listed headers and the encoded body are stored; no Java
 *       serialization is used;
 *   <li>no {@code KEYS} or {@code SCAN}; records expire through Redis TTLs;
 *   <li>the application's {@link RedisConnectionFactory} (and therefore its pool, TLS settings and
 *       command timeout) is reused; no connections are created by this class.
 * </ul>
 *
 * <p>Requires Redis 7.0 or later (Lua script effects replication).
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

  /** Version segment of the key layout; changes only with an incompatible record format. */
  static final String LAYOUT_VERSION = "v1:";

  private static final String SCRIPT_PATH = "io/github/tanmaysinghx/zynqo/redis/";

  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> CLAIM = script("claim.lua", List.class);

  private static final RedisScript<Long> COMPLETE = script("complete.lua", Long.class);
  private static final RedisScript<Long> RELEASE = script("release.lua", Long.class);
  private static final RedisScript<Long> FAIL = script("fail.lua", Long.class);

  private final RedisTemplate<String, byte[]> redis;
  private final String keyPrefix;

  /**
   * Creates a store.
   *
   * @param connectionFactory the application's connection factory
   * @param keyPrefix namespace prepended to every key, for example {@code "zynqo:"}
   */
  public RedisIdempotencyStore(RedisConnectionFactory connectionFactory, String keyPrefix) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    Objects.requireNonNull(keyPrefix, "keyPrefix");
    if (keyPrefix.isBlank()) {
      throw new IllegalArgumentException("keyPrefix must not be blank");
    }
    RedisTemplate<String, byte[]> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(RedisSerializer.string());
    template.setValueSerializer(RedisSerializer.byteArray());
    template.setHashKeySerializer(RedisSerializer.string());
    template.setHashValueSerializer(RedisSerializer.byteArray());
    template.setEnableDefaultSerializer(false);
    template.afterPropertiesSet();
    this.redis = template;
    this.keyPrefix = keyPrefix;
  }

  /**
   * Returns the Redis key that stores a record.
   *
   * @param key the record identifier
   * @return the Redis key
   */
  public String redisKey(IdempotencyKey key) {
    return keyPrefix + LAYOUT_VERSION + key.id();
  }

  @Override
  public ClaimResult claim(
      IdempotencyKey key,
      RequestFingerprint fingerprint,
      String owner,
      Duration lease,
      Duration recordTtl) {
    List<?> reply =
        call(
            "claim",
            () ->
                redis.execute(
                    CLAIM,
                    RedisSerializer.byteArray(),
                    rawReplies(),
                    List.of(redisKey(key)),
                    utf8(fingerprint.value()),
                    utf8(owner),
                    millis(lease),
                    millis(recordTtl)));
    try {
      return parseClaim(reply, owner);
    } catch (RuntimeException ex) {
      throw new ZynqoStorageException("Unexpected claim reply from Redis", ex);
    }
  }

  @Override
  public boolean complete(
      IdempotencyKey key, OwnershipToken token, StoredResponse response, Duration retention) {
    byte[] headers = HeaderCodec.encode(response.headers());
    byte[] body = response.body();
    return applied(
        call(
            "complete",
            () ->
                redis.execute(
                    COMPLETE,
                    RedisSerializer.byteArray(),
                    rawReplies(),
                    List.of(redisKey(key)),
                    utf8(token.owner()),
                    utf8(Long.toString(token.fence())),
                    millis(retention),
                    utf8(Integer.toString(response.status())),
                    headers,
                    utf8(body == null ? "0" : "1"),
                    body == null ? new byte[0] : body)));
  }

  @Override
  public boolean release(IdempotencyKey key, OwnershipToken token) {
    return applied(
        call(
            "release",
            () ->
                redis.execute(
                    RELEASE,
                    RedisSerializer.byteArray(),
                    rawReplies(),
                    List.of(redisKey(key)),
                    utf8(token.owner()),
                    utf8(Long.toString(token.fence())))));
  }

  @Override
  public boolean fail(IdempotencyKey key, OwnershipToken token, Duration retention) {
    return applied(
        call(
            "fail",
            () ->
                redis.execute(
                    FAIL,
                    RedisSerializer.byteArray(),
                    rawReplies(),
                    List.of(redisKey(key)),
                    utf8(token.owner()),
                    utf8(Long.toString(token.fence())),
                    millis(retention))));
  }

  private static ClaimResult parseClaim(List<?> reply, String owner) {
    String status = text(reply.get(0));
    return switch (status) {
      case "ACQUIRED" ->
          new ClaimResult.Acquired(
              new OwnershipToken(owner, Long.parseLong(text(reply.get(1)))), false);
      case "RECOVERED" ->
          new ClaimResult.Acquired(
              new OwnershipToken(owner, Long.parseLong(text(reply.get(1)))), true);
      case "IN_PROGRESS" ->
          new ClaimResult.InProgress(Duration.ofMillis(Long.parseLong(text(reply.get(1)))));
      case "MISMATCH" -> new ClaimResult.FingerprintMismatch();
      case "FAILED" -> new ClaimResult.Failed();
      case "COMPLETED" ->
          new ClaimResult.Completed(
              new StoredResponse(
                  Integer.parseInt(text(reply.get(1))),
                  HeaderCodec.decode(bytes(reply.get(2))),
                  "1".equals(text(reply.get(3))) ? bytes(reply.get(4)) : null));
      default -> throw new IllegalStateException("record in unexpected state: " + status);
    };
  }

  private static boolean applied(Long result) {
    if (result == null) {
      throw new ZynqoStorageException("Redis returned no result", null);
    }
    return result == 1L;
  }

  private static <T> T call(String operation, Supplier<T> call) {
    try {
      return call.get();
    } catch (DataAccessException ex) {
      throw new ZynqoStorageException(
          "Redis " + operation + " failed: " + ex.getClass().getSimpleName(), ex);
    }
  }

  /**
   * Returns a pass-through result serializer: bulk-string reply elements stay {@code byte[]} and
   * integer replies are returned as {@link Long} without deserialization.
   */
  @SuppressWarnings("unchecked")
  private static <T> RedisSerializer<T> rawReplies() {
    return (RedisSerializer<T>) RedisSerializer.byteArray();
  }

  private static <T> RedisScript<T> script(String name, Class<T> resultType) {
    DefaultRedisScript<T> script = new DefaultRedisScript<>();
    script.setLocation(
        new ClassPathResource(SCRIPT_PATH + name, RedisIdempotencyStore.class.getClassLoader()));
    script.setResultType(resultType);
    return script;
  }

  private static byte[] millis(Duration duration) {
    long millis = duration.toMillis();
    if (millis < 1) {
      throw new IllegalArgumentException("durations must be at least one millisecond");
    }
    return utf8(Long.toString(millis));
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(Object element) {
    if (element instanceof byte[] bytes) {
      return bytes;
    }
    throw new IllegalStateException("expected a bulk string reply element");
  }

  private static String text(Object element) {
    return new String(bytes(element), StandardCharsets.UTF_8);
  }
}
