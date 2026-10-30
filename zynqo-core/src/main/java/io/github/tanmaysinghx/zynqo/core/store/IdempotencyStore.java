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
package io.github.tanmaysinghx.zynqo.core.store;

import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import java.time.Duration;

/**
 * Storage SPI for idempotency records.
 *
 * <p>Implementations must make every method atomic with respect to concurrent callers in any
 * process sharing the store, and must base lease expiry on a single clock (the store's), never on
 * the callers' clocks.
 *
 * <p>Infrastructure failures are reported as {@link ZynqoStorageException}. Implementations must
 * not retry state-changing operations in ways that could apply them twice.
 */
public interface IdempotencyStore {

  /**
   * Atomically claims the record for execution, or reports its current state.
   *
   * <ul>
   *   <li>absent record: create {@code IN_PROGRESS} with fence 1 and return {@link
   *       ClaimResult.Acquired};
   *   <li>fingerprint differs: return {@link ClaimResult.FingerprintMismatch} (regardless of
   *       state);
   *   <li>{@code COMPLETED}: return {@link ClaimResult.Completed};
   *   <li>{@code FAILED}: return {@link ClaimResult.Failed};
   *   <li>{@code IN_PROGRESS} with a live lease: return {@link ClaimResult.InProgress};
   *   <li>{@code IN_PROGRESS} with an expired lease: install the new owner, increment the fence,
   *       and return {@link ClaimResult.Acquired} with {@code recovered = true}.
   * </ul>
   *
   * @param key the record identifier
   * @param fingerprint the fingerprint of the current request
   * @param owner a fresh random owner identifier for this attempt
   * @param lease how long the claim is exclusive
   * @param recordTtl how long the in-progress record itself is retained; at least {@code lease}
   * @return the claim outcome
   * @throws ZynqoStorageException if the store cannot be reached or returns an invalid record
   */
  ClaimResult claim(
      IdempotencyKey key,
      RequestFingerprint fingerprint,
      String owner,
      Duration lease,
      Duration recordTtl);

  /**
   * Atomically transitions an owned {@code IN_PROGRESS} record to {@code COMPLETED}.
   *
   * @param key the record identifier
   * @param token the token returned by the claim
   * @param response the response to store
   * @param retention how long the completed record is retained
   * @return {@code true} if the transition was applied, {@code false} if the caller no longer owns
   *     the record (stale owner, record expired, or record in another state)
   * @throws ZynqoStorageException if the store cannot be reached
   */
  boolean complete(
      IdempotencyKey key, OwnershipToken token, StoredResponse response, Duration retention);

  /**
   * Atomically deletes an owned {@code IN_PROGRESS} record so the operation may be retried.
   *
   * @param key the record identifier
   * @param token the token returned by the claim
   * @return {@code true} if the record was deleted, {@code false} if the caller no longer owns it
   * @throws ZynqoStorageException if the store cannot be reached
   */
  boolean release(IdempotencyKey key, OwnershipToken token);

  /**
   * Atomically transitions an owned {@code IN_PROGRESS} record to {@code FAILED}.
   *
   * @param key the record identifier
   * @param token the token returned by the claim
   * @param retention how long the failed record is retained
   * @return {@code true} if the transition was applied, {@code false} if the caller no longer owns
   *     the record
   * @throws ZynqoStorageException if the store cannot be reached
   */
  boolean fail(IdempotencyKey key, OwnershipToken token, Duration retention);
}
