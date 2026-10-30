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

import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.ClaimResult;
import io.github.tanmaysinghx.zynqo.core.store.IdempotencyStore;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import io.github.tanmaysinghx.zynqo.core.store.ZynqoStorageException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes operations under the idempotency state machine.
 *
 * <p>For one request the engine:
 *
 * <ol>
 *   <li>atomically claims the record; if the store is unavailable, refuses execution (fail-closed);
 *   <li>replays a completed response, rejects a fingerprint mismatch, rejects a failed record, or
 *       waits (bounded) for an in-progress duplicate;
 *   <li>executes the operation exactly once per successful claim;
 *   <li>on success, encodes and stores the result with an ownership-checked transition; on failure,
 *       releases or marks the record failed according to the {@link ExceptionClassifier}.
 * </ol>
 *
 * <p>The engine never retries the protected operation. Errors from the store after the operation
 * has run are logged and reported to the {@link ZynqoObserver}, and the operation's real result is
 * returned: the caller must not be told that an executed operation failed.
 *
 * <p>Instances are thread-safe.
 */
public final class IdempotencyEngine {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyEngine.class);
  private static final Duration STORAGE_RETRY_AFTER = Duration.ofSeconds(1);

  private final IdempotencyStore store;
  private final ExceptionClassifier classifier;
  private final ZynqoObserver observer;
  private final WaitStrategy waitStrategy;
  private final LongSupplier nanoClock;
  private final Supplier<String> ownerGenerator;

  private IdempotencyEngine(Builder builder) {
    this.store = builder.store;
    this.classifier = builder.classifier;
    this.observer = builder.observer;
    this.waitStrategy = builder.waitStrategy;
    this.nanoClock = builder.nanoClock;
    this.ownerGenerator = builder.ownerGenerator;
  }

  /**
   * Starts building an engine.
   *
   * @param store the idempotency store
   * @return a new builder
   */
  public static Builder builder(IdempotencyStore store) {
    return new Builder(store);
  }

  /**
   * Executes an operation idempotently.
   *
   * @param key the record identifier
   * @param fingerprint the request fingerprint
   * @param policy timing and size limits
   * @param codec converts results to and from their stored form
   * @param action the protected operation
   * @param <T> the result type
   * @return the result and how it was obtained
   * @throws ZynqoException if the request is refused
   * @throws Throwable any exception thrown by the operation, unchanged
   */
  public <T> IdempotentResult<T> execute(
      IdempotencyKey key,
      RequestFingerprint fingerprint,
      ExecutionPolicy policy,
      ResponseCodec<T> codec,
      IdempotentAction<T> action)
      throws Throwable {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(codec, "codec");
    Objects.requireNonNull(action, "action");

    long deadline = nanoClock.getAsLong() + policy.waitTimeout().toNanos();
    while (true) {
      ClaimResult claim = claim(key, fingerprint, policy);
      if (claim instanceof ClaimResult.Acquired acquired) {
        return executeOwned(key, acquired, policy, codec, action);
      }
      if (claim instanceof ClaimResult.Completed completed) {
        return replay(key, completed.response(), codec);
      }
      if (claim instanceof ClaimResult.FingerprintMismatch) {
        observe(Outcome.FINGERPRINT_MISMATCH);
        throw new ZynqoException(
            ZynqoErrorCode.FINGERPRINT_MISMATCH,
            "The idempotency key was already used with a different request payload.");
      }
      if (claim instanceof ClaimResult.Failed) {
        observe(Outcome.PREVIOUSLY_FAILED);
        throw new ZynqoException(
            ZynqoErrorCode.PREVIOUS_ATTEMPT_FAILED,
            "A previous request with this idempotency key did not complete successfully and"
                + " will not be executed again. Use a new idempotency key after verifying the"
                + " outcome of the previous request.");
      }
      Duration leaseRemaining = ((ClaimResult.InProgress) claim).leaseRemaining();
      long remaining = deadline - nanoClock.getAsLong();
      if (remaining <= 0) {
        throw inProgress(leaseRemaining);
      }
      pauseBeforeRetry(
          Duration.ofNanos(Math.min(remaining, policy.pollInterval().toNanos())), leaseRemaining);
    }
  }

  private ClaimResult claim(
      IdempotencyKey key, RequestFingerprint fingerprint, ExecutionPolicy policy) {
    long start = nanoClock.getAsLong();
    try {
      ClaimResult result =
          store.claim(
              key, fingerprint, ownerGenerator.get(), policy.lease(), policy.inProgressRecordTtl());
      storageObserved(StorageOperation.CLAIM, start, true);
      return Objects.requireNonNull(result, "store returned null claim result");
    } catch (ZynqoStorageException ex) {
      storageObserved(StorageOperation.CLAIM, start, false);
      observe(Outcome.STORAGE_UNAVAILABLE);
      log.warn("Idempotency store unavailable while claiming {}; refusing execution", key, ex);
      throw new ZynqoException(
          ZynqoErrorCode.STORAGE_UNAVAILABLE,
          "Idempotency could not be guaranteed because the idempotency store is unavailable."
              + " The request was not executed.",
          STORAGE_RETRY_AFTER,
          ex);
    }
  }

  private <T> IdempotentResult<T> executeOwned(
      IdempotencyKey key,
      ClaimResult.Acquired acquired,
      ExecutionPolicy policy,
      ResponseCodec<T> codec,
      IdempotentAction<T> action)
      throws Throwable {
    OwnershipToken token = acquired.token();
    if (acquired.recovered()) {
      log.warn(
          "Took over expired lease for {} ({}); the previous owner may still be running or may"
              + " have crashed",
          key,
          token);
    }

    T result;
    long start = nanoClock.getAsLong();
    Long previousFence = ZynqoContext.enter(token.fence());
    try {
      result = action.execute();
    } catch (Throwable failure) {
      observer(o -> o.onExecution(elapsed(start), false));
      handleFailure(key, token, policy, failure);
      observe(Outcome.EXECUTION_FAILED);
      throw failure;
    } finally {
      ZynqoContext.exit(previousFence);
    }
    observer(o -> o.onExecution(elapsed(start), true));

    StoredResponse stored;
    try {
      stored = codec.encode(result);
      if (stored.bodySize() > policy.maxResponseBytes()) {
        throw new IllegalArgumentException(
            "encoded response of "
                + stored.bodySize()
                + " bytes exceeds the limit of "
                + policy.maxResponseBytes()
                + " bytes");
      }
    } catch (RuntimeException ex) {
      log.error(
          "Response for {} cannot be stored ({}); marking the record failed so the operation is"
              + " not executed again",
          key,
          ex.toString());
      transition(StorageOperation.FAIL, () -> store.fail(key, token, policy.failedRetention()));
      observe(Outcome.NOT_STORED);
      return new IdempotentResult<>(result, Outcome.NOT_STORED);
    }

    Transition completion =
        transition(
            StorageOperation.COMPLETE,
            () -> store.complete(key, token, stored, policy.completedRetention()));
    Outcome outcome;
    if (completion == Transition.STORE_FAILED) {
      log.error(
          "Operation for {} executed but completion could not be recorded; a retry after the lease"
              + " expires will execute it again unless the application prevents it",
          key);
      outcome = Outcome.COMPLETION_ERROR;
    } else if (completion == Transition.REJECTED) {
      log.warn(
          "Operation for {} executed but {} no longer owns the record (lease expired and was taken"
              + " over, or the record expired); the result was not stored",
          key,
          token);
      outcome = Outcome.COMPLETION_REJECTED;
    } else {
      outcome = acquired.recovered() ? Outcome.RECOVERED : Outcome.EXECUTED;
    }
    observe(outcome);
    return new IdempotentResult<>(result, outcome);
  }

  private void handleFailure(
      IdempotencyKey key, OwnershipToken token, ExecutionPolicy policy, Throwable failure) {
    ExceptionDisposition disposition;
    try {
      disposition = Objects.requireNonNull(classifier.classify(failure), "disposition");
    } catch (RuntimeException ex) {
      log.error("Exception classifier failed; marking record {} failed", key, ex);
      disposition = ExceptionDisposition.MARK_FAILED;
    }
    Transition applied =
        switch (disposition) {
          case RELEASE -> transition(StorageOperation.RELEASE, () -> store.release(key, token));
          case MARK_FAILED ->
              transition(
                  StorageOperation.FAIL, () -> store.fail(key, token, policy.failedRetention()));
        };
    if (applied == Transition.STORE_FAILED) {
      log.error(
          "Could not apply {} to {} after the operation failed; the record stays in progress until"
              + " its lease expires",
          disposition,
          key);
    } else if (applied == Transition.REJECTED) {
      log.warn("Could not apply {} to {}: ownership was lost", disposition, key);
    }
  }

  private <T> IdempotentResult<T> replay(
      IdempotencyKey key, StoredResponse response, ResponseCodec<T> codec) {
    T value;
    try {
      value = codec.decode(response);
    } catch (RuntimeException ex) {
      observe(Outcome.REPLAY_FAILED);
      log.error("Stored response for {} cannot be decoded", key, ex);
      throw new ZynqoException(
          ZynqoErrorCode.REPLAY_FAILED,
          "The stored response for this idempotency key cannot be replayed.",
          null,
          ex);
    }
    observe(Outcome.REPLAYED);
    return new IdempotentResult<>(value, Outcome.REPLAYED);
  }

  private void pauseBeforeRetry(Duration pause, Duration leaseRemaining) {
    try {
      waitStrategy.pause(pause);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw inProgress(leaseRemaining);
    }
  }

  private ZynqoException inProgress(Duration leaseRemaining) {
    observe(Outcome.IN_PROGRESS);
    long seconds = Math.max(1, (leaseRemaining.toMillis() + 999) / 1000);
    return new ZynqoException(
        ZynqoErrorCode.REQUEST_IN_PROGRESS,
        "A request with this idempotency key is currently being processed. Retry later.",
        Duration.ofSeconds(Math.min(seconds, 60)),
        null);
  }

  /** Result of an ownership-checked state transition. */
  private enum Transition {
    APPLIED,
    REJECTED,
    STORE_FAILED
  }

  /** Runs an ownership-checked transition, converting store failures into a result. */
  private Transition transition(StorageOperation operation, BooleanSupplier call) {
    long start = nanoClock.getAsLong();
    try {
      boolean applied = call.getAsBoolean();
      storageObserved(operation, start, true);
      return applied ? Transition.APPLIED : Transition.REJECTED;
    } catch (ZynqoStorageException ex) {
      storageObserved(operation, start, false);
      log.error("Idempotency store failed during {}", operation.tagValue(), ex);
      return Transition.STORE_FAILED;
    }
  }

  private Duration elapsed(long startNanos) {
    return Duration.ofNanos(nanoClock.getAsLong() - startNanos);
  }

  private void storageObserved(StorageOperation operation, long startNanos, boolean success) {
    Duration duration = elapsed(startNanos);
    observer(o -> o.onStorageOperation(operation, duration, success));
  }

  private void observe(Outcome outcome) {
    observer(o -> o.onOutcome(outcome));
  }

  private void observer(Consumer<ZynqoObserver> event) {
    try {
      event.accept(observer);
    } catch (RuntimeException ex) {
      log.warn("ZynqoObserver threw; ignoring", ex);
    }
  }

  /** Builder for {@link IdempotencyEngine}. */
  public static final class Builder {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdempotencyStore store;
    private ExceptionClassifier classifier =
        ExceptionClassifier.always(ExceptionDisposition.RELEASE);
    private ZynqoObserver observer = ZynqoObserver.NOOP;
    private WaitStrategy waitStrategy = WaitStrategy.SLEEP;
    private LongSupplier nanoClock = System::nanoTime;
    private Supplier<String> ownerGenerator = Builder::randomOwner;

    private Builder(IdempotencyStore store) {
      this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Sets the exception classifier. Defaults to {@link ExceptionDisposition#RELEASE} for every
     * failure.
     *
     * @param classifier the classifier
     * @return this builder
     */
    public Builder exceptionClassifier(ExceptionClassifier classifier) {
      this.classifier = Objects.requireNonNull(classifier, "classifier");
      return this;
    }

    /**
     * Sets the observer. Defaults to {@link ZynqoObserver#NOOP}.
     *
     * @param observer the observer
     * @return this builder
     */
    public Builder observer(ZynqoObserver observer) {
      this.observer = Objects.requireNonNull(observer, "observer");
      return this;
    }

    /**
     * Sets the wait strategy used between checks of an in-progress duplicate. Defaults to {@link
     * WaitStrategy#SLEEP}.
     *
     * @param waitStrategy the wait strategy
     * @return this builder
     */
    public Builder waitStrategy(WaitStrategy waitStrategy) {
      this.waitStrategy = Objects.requireNonNull(waitStrategy, "waitStrategy");
      return this;
    }

    /**
     * Sets the monotonic clock in nanoseconds. Defaults to {@link System#nanoTime()}.
     *
     * @param nanoClock the clock
     * @return this builder
     */
    public Builder nanoClock(LongSupplier nanoClock) {
      this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
      return this;
    }

    /**
     * Sets the generator of owner identifiers. Defaults to 128 random bits from {@link
     * SecureRandom}. Generated values must be unique and unguessable.
     *
     * @param ownerGenerator the generator
     * @return this builder
     */
    public Builder ownerGenerator(Supplier<String> ownerGenerator) {
      this.ownerGenerator = Objects.requireNonNull(ownerGenerator, "ownerGenerator");
      return this;
    }

    /**
     * Builds the engine.
     *
     * @return the engine
     */
    public IdempotencyEngine build() {
      return new IdempotencyEngine(this);
    }

    private static String randomOwner() {
      byte[] bytes = new byte[16];
      RANDOM.nextBytes(bytes);
      return HexFormat.of().formatHex(bytes);
    }
  }
}
