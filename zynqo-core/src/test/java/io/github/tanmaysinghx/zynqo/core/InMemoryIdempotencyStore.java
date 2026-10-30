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
package io.github.tanmaysinghx.zynqo.core;

import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.ClaimResult;
import io.github.tanmaysinghx.zynqo.core.store.IdempotencyStore;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import io.github.tanmaysinghx.zynqo.core.store.ZynqoStorageException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test double implementing the {@link IdempotencyStore} contract in memory with a manually advanced
 * clock. Used only to unit test the engine; the Redis implementation is verified against a real
 * Redis server.
 */
final class InMemoryIdempotencyStore implements IdempotencyStore {

  enum State {
    IN_PROGRESS,
    COMPLETED,
    FAILED
  }

  static final class Record {
    State state;
    RequestFingerprint fingerprint;
    String owner;
    long fence;
    long leaseUntil;
    long expiresAt;
    StoredResponse response;
  }

  final AtomicLong nowMillis = new AtomicLong(1_000_000);
  private final Map<IdempotencyKey, Record> records = new HashMap<>();
  RuntimeException failNextWith;
  RuntimeException failClaimWith;
  RuntimeException failCompleteWith;
  RuntimeException failReleaseWith;

  void advance(Duration duration) {
    nowMillis.addAndGet(duration.toMillis());
  }

  synchronized Record record(IdempotencyKey key) {
    Record record = records.get(key);
    if (record != null && record.expiresAt <= nowMillis.get()) {
      records.remove(key);
      return null;
    }
    return record;
  }

  @Override
  public synchronized ClaimResult claim(
      IdempotencyKey key,
      RequestFingerprint fingerprint,
      String owner,
      Duration lease,
      Duration recordTtl) {
    maybeFail(failClaimWith);
    long now = nowMillis.get();
    Record record = record(key);
    if (record == null) {
      record = new Record();
      record.state = State.IN_PROGRESS;
      record.fingerprint = fingerprint;
      record.owner = owner;
      record.fence = 1;
      record.leaseUntil = now + lease.toMillis();
      record.expiresAt = now + recordTtl.toMillis();
      records.put(key, record);
      return new ClaimResult.Acquired(new OwnershipToken(owner, 1), false);
    }
    if (!record.fingerprint.equals(fingerprint)) {
      return new ClaimResult.FingerprintMismatch();
    }
    return switch (record.state) {
      case COMPLETED -> new ClaimResult.Completed(record.response);
      case FAILED -> new ClaimResult.Failed();
      case IN_PROGRESS -> {
        if (record.leaseUntil > now) {
          yield new ClaimResult.InProgress(Duration.ofMillis(record.leaseUntil - now));
        }
        record.fence++;
        record.owner = owner;
        record.leaseUntil = now + lease.toMillis();
        record.expiresAt = now + recordTtl.toMillis();
        yield new ClaimResult.Acquired(new OwnershipToken(owner, record.fence), true);
      }
    };
  }

  @Override
  public synchronized boolean complete(
      IdempotencyKey key, OwnershipToken token, StoredResponse response, Duration retention) {
    maybeFail(failCompleteWith);
    Record record = owned(key, token);
    if (record == null) {
      return false;
    }
    record.state = State.COMPLETED;
    record.response = response;
    record.owner = null;
    record.expiresAt = nowMillis.get() + retention.toMillis();
    return true;
  }

  @Override
  public synchronized boolean release(IdempotencyKey key, OwnershipToken token) {
    maybeFail(failReleaseWith);
    if (owned(key, token) == null) {
      return false;
    }
    records.remove(key);
    return true;
  }

  @Override
  public synchronized boolean fail(IdempotencyKey key, OwnershipToken token, Duration retention) {
    maybeFail(failNextWith);
    Record record = owned(key, token);
    if (record == null) {
      return false;
    }
    record.state = State.FAILED;
    record.owner = null;
    record.expiresAt = nowMillis.get() + retention.toMillis();
    return true;
  }

  private Record owned(IdempotencyKey key, OwnershipToken token) {
    Record record = record(key);
    if (record == null
        || record.state != State.IN_PROGRESS
        || !token.owner().equals(record.owner)
        || token.fence() != record.fence) {
      return null;
    }
    return record;
  }

  private static void maybeFail(RuntimeException ex) {
    if (ex != null) {
      throw ex instanceof ZynqoStorageException ? ex : new ZynqoStorageException("boom", ex);
    }
  }
}
