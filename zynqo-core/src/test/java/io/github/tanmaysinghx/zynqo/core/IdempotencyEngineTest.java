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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyKey;
import io.github.tanmaysinghx.zynqo.core.identity.IdempotencyScope;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import io.github.tanmaysinghx.zynqo.core.store.ZynqoStorageException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdempotencyEngineTest {

  private static final IdempotencyKey KEY =
      IdempotencyKey.derive(new IdempotencyScope("t", "alice", "POST /orders"), "k-1");
  private static final RequestFingerprint FP = fingerprint("a");
  private static final RequestFingerprint OTHER_FP = fingerprint("b");

  private static final ExecutionPolicy POLICY =
      new ExecutionPolicy(
          Duration.ofSeconds(30),
          Duration.ofHours(1),
          Duration.ofHours(2),
          Duration.ZERO,
          Duration.ofMillis(50),
          1024);

  private static final ResponseCodec<String> CODEC =
      new ResponseCodec<>() {
        @Override
        public StoredResponse encode(String result) {
          return new StoredResponse(
              201,
              Map.of("Location", List.of("/orders/1")),
              result == null ? null : result.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decode(StoredResponse response) {
          byte[] body = response.body();
          return body == null ? null : new String(body, StandardCharsets.UTF_8);
        }
      };

  private InMemoryIdempotencyStore store;
  private RecordingObserver observer;
  private AtomicLong nanos;
  private IdempotencyEngine engine;

  @BeforeEach
  void setUp() {
    store = new InMemoryIdempotencyStore();
    observer = new RecordingObserver();
    nanos = new AtomicLong();
    engine = engine(ExceptionClassifier.always(ExceptionDisposition.RELEASE), d -> {});
  }

  private IdempotencyEngine engine(ExceptionClassifier classifier, WaitStrategy waitStrategy) {
    return IdempotencyEngine.builder(store)
        .exceptionClassifier(classifier)
        .observer(observer)
        .waitStrategy(waitStrategy)
        .nanoClock(nanos::get)
        .build();
  }

  private static RequestFingerprint fingerprint(String body) {
    return RequestFingerprint.builder().add("body", body).build();
  }

  @Test
  void firstRequestExecutesAndDuplicateReplaysStoredResult() throws Throwable {
    AtomicInteger executions = new AtomicInteger();
    IdempotentAction<String> action = () -> "order-" + executions.incrementAndGet();

    IdempotentResult<String> first = engine.execute(KEY, FP, POLICY, CODEC, action);
    IdempotentResult<String> second = engine.execute(KEY, FP, POLICY, CODEC, action);

    assertThat(first.value()).isEqualTo("order-1");
    assertThat(first.outcome()).isEqualTo(Outcome.EXECUTED);
    assertThat(first.replayed()).isFalse();
    assertThat(second.value()).isEqualTo("order-1");
    assertThat(second.replayed()).isTrue();
    assertThat(executions).hasValue(1);
    assertThat(observer.outcomes).containsExactly(Outcome.EXECUTED, Outcome.REPLAYED);
    assertThat(observer.executions).hasValue(1);
  }

  @Test
  void completedRecordIsRetainedForConfiguredRetentionOnly() throws Throwable {
    AtomicInteger executions = new AtomicInteger();
    IdempotentAction<String> action = () -> "order-" + executions.incrementAndGet();
    engine.execute(KEY, FP, POLICY, CODEC, action);

    store.advance(POLICY.completedRetention());

    assertThat(engine.execute(KEY, FP, POLICY, CODEC, action).value()).isEqualTo("order-2");
  }

  @Test
  void sameKeyWithDifferentFingerprintIsRejected() throws Throwable {
    engine.execute(KEY, FP, POLICY, CODEC, () -> "x");

    ZynqoException ex =
        catchThrowableOfType(
            ZynqoException.class, () -> engine.execute(KEY, OTHER_FP, POLICY, CODEC, () -> "y"));

    assertThat(ex.code()).isEqualTo(ZynqoErrorCode.FINGERPRINT_MISMATCH);
    assertThat(ex.retryAfter()).isEmpty();
    assertThat(observer.outcomes).endsWith(Outcome.FINGERPRINT_MISMATCH);
  }

  @Test
  void duplicateWhileInProgressIsRejectedImmediatelyWithRetryAfter() throws Throwable {
    List<ZynqoException> nested = new ArrayList<>();
    engine.execute(
        KEY,
        FP,
        POLICY,
        CODEC,
        () -> {
          nested.add(
              catchThrowableOfType(
                  ZynqoException.class,
                  () -> engine.execute(KEY, FP, POLICY, CODEC, () -> "never")));
          return "done";
        });

    assertThat(nested)
        .singleElement()
        .satisfies(
            ex -> {
              assertThat(ex.code()).isEqualTo(ZynqoErrorCode.REQUEST_IN_PROGRESS);
              assertThat(ex.retryAfter()).contains(Duration.ofSeconds(30));
            });
  }

  @Test
  void retryAfterIsAtLeastOneSecondAndAtMostSixtySeconds() throws Throwable {
    ExecutionPolicy longLease = POLICY.withLease(Duration.ofMinutes(10));
    List<ZynqoException> nested = new ArrayList<>();
    engine.execute(
        KEY,
        FP,
        longLease,
        CODEC,
        () -> {
          nested.add(
              catchThrowableOfType(
                  ZynqoException.class,
                  () -> engine.execute(KEY, FP, longLease, CODEC, () -> "never")));
          store.advance(Duration.ofMinutes(10).minusMillis(1));
          nested.add(
              catchThrowableOfType(
                  ZynqoException.class,
                  () -> engine.execute(KEY, FP, longLease, CODEC, () -> "never")));
          return "done";
        });

    assertThat(nested.get(0).retryAfter()).contains(Duration.ofSeconds(60));
    assertThat(nested.get(1).retryAfter()).contains(Duration.ofSeconds(1));
  }

  @Test
  void duplicateWaitsBoundedTimeAndReplaysWhenOwnerCompletes() throws Throwable {
    ExecutionPolicy waiting =
        new ExecutionPolicy(
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(100),
            1024);
    // Another node owns the record and completes it while this request is waiting.
    store.claim(KEY, FP, "other-node", Duration.ofSeconds(30), Duration.ofHours(1));
    AtomicInteger pauses = new AtomicInteger();
    IdempotencyEngine waitingEngine =
        engine(
            ExceptionClassifier.always(ExceptionDisposition.RELEASE),
            pause -> {
              assertThat(pause).isEqualTo(Duration.ofMillis(100));
              nanos.addAndGet(pause.toNanos());
              if (pauses.incrementAndGet() == 2) {
                store.complete(
                    KEY,
                    new OwnershipToken("other-node", 1),
                    CODEC.encode("from-other-node"),
                    Duration.ofHours(1));
              }
            });

    IdempotentResult<String> result = waitingEngine.execute(KEY, FP, waiting, CODEC, () -> "never");

    assertThat(result.value()).isEqualTo("from-other-node");
    assertThat(result.replayed()).isTrue();
    assertThat(pauses).hasValue(2);
  }

  @Test
  void duplicateGivesUpWhenWaitTimeoutElapses() {
    ExecutionPolicy waiting =
        new ExecutionPolicy(
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofHours(1),
            Duration.ofMillis(250),
            Duration.ofMillis(100),
            1024);
    List<Duration> pauses = new ArrayList<>();
    IdempotencyEngine waitingEngine =
        engine(
            ExceptionClassifier.always(ExceptionDisposition.RELEASE),
            pause -> {
              pauses.add(pause);
              nanos.addAndGet(pause.toNanos());
            });
    store.claim(KEY, FP, "owner", Duration.ofSeconds(30), Duration.ofHours(1));

    ZynqoException ex =
        catchThrowableOfType(
            ZynqoException.class,
            () -> waitingEngine.execute(KEY, FP, waiting, CODEC, () -> "never"));

    assertThat(ex.code()).isEqualTo(ZynqoErrorCode.REQUEST_IN_PROGRESS);
    assertThat(pauses)
        .containsExactly(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(50));
  }

  @Test
  void interruptedWaitIsRejectedAndPreservesInterruptFlag() {
    ExecutionPolicy waiting =
        new ExecutionPolicy(
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofHours(1),
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            1024);
    IdempotencyEngine waitingEngine =
        engine(
            ExceptionClassifier.always(ExceptionDisposition.RELEASE),
            pause -> {
              throw new InterruptedException();
            });
    store.claim(KEY, FP, "owner", Duration.ofSeconds(30), Duration.ofHours(1));

    try {
      assertThatThrownBy(() -> waitingEngine.execute(KEY, FP, waiting, CODEC, () -> "never"))
          .isInstanceOfSatisfying(
              ZynqoException.class,
              ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.REQUEST_IN_PROGRESS));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void releasedRecordAllowsRetryAfterBusinessException() throws Throwable {
    IllegalStateException failure = new IllegalStateException("validation");

    assertThatThrownBy(
            () ->
                engine.execute(
                    KEY,
                    FP,
                    POLICY,
                    CODEC,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(store.record(KEY)).isNull();

    assertThat(engine.execute(KEY, FP, POLICY, CODEC, () -> "retried").outcome())
        .isEqualTo(Outcome.EXECUTED);
    assertThat(observer.outcomes).containsExactly(Outcome.EXECUTION_FAILED, Outcome.EXECUTED);
    assertThat(observer.failedExecutions).hasValue(1);
  }

  @Test
  void markFailedBlocksReexecution() {
    IdempotencyEngine failing =
        engine(ExceptionClassifier.always(ExceptionDisposition.MARK_FAILED), d -> {});
    Exception failure = new Exception("downstream timeout");

    assertThatThrownBy(
            () ->
                failing.execute(
                    KEY,
                    FP,
                    POLICY,
                    CODEC,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    ZynqoException ex =
        catchThrowableOfType(
            ZynqoException.class, () -> failing.execute(KEY, FP, POLICY, CODEC, () -> "again"));
    assertThat(ex.code()).isEqualTo(ZynqoErrorCode.PREVIOUS_ATTEMPT_FAILED);
    assertThat(observer.outcomes).endsWith(Outcome.PREVIOUSLY_FAILED);

    store.advance(POLICY.failedRetention());
    assertThat(store.record(KEY)).isNull();
  }

  @Test
  void classifierFailureFallsBackToMarkFailed() {
    IdempotencyEngine broken =
        engine(
            failure -> {
              throw new IllegalStateException("classifier bug");
            },
            d -> {});

    assertThatThrownBy(
            () ->
                broken.execute(
                    KEY,
                    FP,
                    POLICY,
                    CODEC,
                    () -> {
                      throw new RuntimeException();
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(store.record(KEY).state).isEqualTo(InMemoryIdempotencyStore.State.FAILED);
  }

  @Test
  void storageFailureBeforeClaimFailsClosedWithoutExecuting() {
    store.failClaimWith = new ZynqoStorageException("connection refused", null);
    AtomicInteger executions = new AtomicInteger();

    ZynqoException ex =
        catchThrowableOfType(
            ZynqoException.class,
            () -> engine.execute(KEY, FP, POLICY, CODEC, () -> "x" + executions.incrementAndGet()));

    assertThat(ex.code()).isEqualTo(ZynqoErrorCode.STORAGE_UNAVAILABLE);
    assertThat(ex.retryAfter()).contains(Duration.ofSeconds(1));
    assertThat(ex.getCause()).isSameAs(store.failClaimWith);
    assertThat(executions).hasValue(0);
    assertThat(observer.outcomes).containsExactly(Outcome.STORAGE_UNAVAILABLE);
    assertThat(observer.failedStorage).containsEntry(StorageOperation.CLAIM, 1);
  }

  @Test
  void encodingFailureMarksRecordFailedAndReturnsRealResult() throws Throwable {
    ResponseCodec<String> unencodable =
        new ResponseCodec<>() {
          @Override
          public StoredResponse encode(String result) {
            throw new IllegalArgumentException("not serializable");
          }

          @Override
          public String decode(StoredResponse response) {
            throw new AssertionError("not reached");
          }
        };

    IdempotentResult<String> result = engine.execute(KEY, FP, POLICY, unencodable, () -> "real");

    assertThat(result.value()).isEqualTo("real");
    assertThat(result.outcome()).isEqualTo(Outcome.NOT_STORED);
    assertThat(store.record(KEY).state).isEqualTo(InMemoryIdempotencyStore.State.FAILED);
    assertThatThrownBy(() -> engine.execute(KEY, FP, POLICY, CODEC, () -> "again"))
        .isInstanceOfSatisfying(
            ZynqoException.class,
            ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.PREVIOUS_ATTEMPT_FAILED));
  }

  @Test
  void oversizedResponseIsNotStored() throws Throwable {
    String large = "x".repeat(POLICY.maxResponseBytes() + 1);

    IdempotentResult<String> result = engine.execute(KEY, FP, POLICY, CODEC, () -> large);

    assertThat(result.value()).isEqualTo(large);
    assertThat(result.outcome()).isEqualTo(Outcome.NOT_STORED);
    assertThat(store.record(KEY).state).isEqualTo(InMemoryIdempotencyStore.State.FAILED);
  }

  @Test
  void responseAtExactSizeLimitIsStored() throws Throwable {
    String exact = "x".repeat(POLICY.maxResponseBytes());

    assertThat(engine.execute(KEY, FP, POLICY, CODEC, () -> exact).outcome())
        .isEqualTo(Outcome.EXECUTED);
  }

  @Test
  void nullResultIsStoredAndReplayed() throws Throwable {
    engine.execute(KEY, FP, POLICY, CODEC, () -> null);

    IdempotentResult<String> replay = engine.execute(KEY, FP, POLICY, CODEC, () -> "never");

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.value()).isNull();
  }

  @Test
  void expiredLeaseIsTakenOverAndStaleOwnerCannotOverwriteNewOwner() throws Throwable {
    List<IdempotentResult<String>> takeover = new ArrayList<>();

    IdempotentResult<String> stale =
        engine.execute(
            KEY,
            FP,
            POLICY,
            CODEC,
            () -> {
              assertThat(ZynqoContext.currentFencingToken()).hasValue(1);
              store.advance(POLICY.lease());
              takeover.add(
                  engine.execute(
                      KEY,
                      FP,
                      POLICY,
                      CODEC,
                      () -> {
                        assertThat(ZynqoContext.currentFencingToken()).hasValue(2);
                        return "new-owner";
                      }));
              assertThat(ZynqoContext.currentFencingToken()).hasValue(1);
              return "stale-owner";
            });

    assertThat(takeover)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.outcome()).isEqualTo(Outcome.RECOVERED);
              assertThat(r.value()).isEqualTo("new-owner");
            });
    assertThat(stale.outcome()).isEqualTo(Outcome.COMPLETION_REJECTED);
    assertThat(stale.value()).isEqualTo("stale-owner");
    assertThat(engine.execute(KEY, FP, POLICY, CODEC, () -> "never").value())
        .isEqualTo("new-owner");
    assertThat(ZynqoContext.currentFencingToken()).isEqualTo(OptionalLong.empty());
  }

  @Test
  void staleOwnerFailureDoesNotReleaseNewOwnersRecord() throws Throwable {
    assertThatThrownBy(
            () ->
                engine.execute(
                    KEY,
                    FP,
                    POLICY,
                    CODEC,
                    () -> {
                      store.advance(POLICY.lease());
                      engine.execute(KEY, FP, POLICY, CODEC, () -> "new-owner");
                      throw new IllegalStateException("stale owner failed late");
                    }))
        .hasMessage("stale owner failed late");

    assertThat(engine.execute(KEY, FP, POLICY, CODEC, () -> "never").value())
        .isEqualTo("new-owner");
  }

  @Test
  void storageFailureOnCompletionReturnsRealResult() throws Throwable {
    store.failCompleteWith = new ZynqoStorageException("timeout", null);

    IdempotentResult<String> result = engine.execute(KEY, FP, POLICY, CODEC, () -> "real");

    assertThat(result.value()).isEqualTo("real");
    assertThat(result.outcome()).isEqualTo(Outcome.COMPLETION_ERROR);
    assertThat(store.record(KEY).state).isEqualTo(InMemoryIdempotencyStore.State.IN_PROGRESS);
    assertThat(observer.failedStorage).containsEntry(StorageOperation.COMPLETE, 1);
  }

  @Test
  void storageFailureOnReleasePropagatesOriginalException() {
    store.failReleaseWith = new ZynqoStorageException("timeout", null);
    IllegalStateException failure = new IllegalStateException("business");

    assertThatThrownBy(
            () ->
                engine.execute(
                    KEY,
                    FP,
                    POLICY,
                    CODEC,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(store.record(KEY).state).isEqualTo(InMemoryIdempotencyStore.State.IN_PROGRESS);
  }

  @Test
  void undecodableStoredResponseIsReportedAsReplayFailure() throws Throwable {
    engine.execute(KEY, FP, POLICY, CODEC, () -> "x");
    ResponseCodec<String> broken =
        new ResponseCodec<>() {
          @Override
          public StoredResponse encode(String result) {
            return CODEC.encode(result);
          }

          @Override
          public String decode(StoredResponse response) {
            throw new IllegalArgumentException("incompatible");
          }
        };

    assertThatThrownBy(() -> engine.execute(KEY, FP, POLICY, broken, () -> "never"))
        .isInstanceOfSatisfying(
            ZynqoException.class,
            ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.REPLAY_FAILED));
    assertThat(observer.outcomes).endsWith(Outcome.REPLAY_FAILED);
  }

  @Test
  void throwingObserverDoesNotAffectExecution() throws Throwable {
    IdempotencyEngine noisy =
        IdempotencyEngine.builder(store)
            .observer(
                new ZynqoObserver() {
                  @Override
                  public void onOutcome(Outcome outcome) {
                    throw new IllegalStateException("observer bug");
                  }
                })
            .build();

    assertThat(noisy.execute(KEY, FP, POLICY, CODEC, () -> "ok").value()).isEqualTo("ok");
  }

  @Test
  void fencingTokenIsAbsentOutsideExecution() {
    assertThat(ZynqoContext.currentFencingToken()).isEmpty();
  }

  @Test
  void hundredsOfConcurrentDuplicatesExecuteOnce() throws Exception {
    int callers = 300;
    IdempotencyEngine concurrent = IdempotencyEngine.builder(store).build();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch othersDone = new CountDownLatch(callers - 1);
    AtomicInteger executions = new AtomicInteger();
    List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
    List<ZynqoErrorCode> rejections = Collections.synchronizedList(new ArrayList<>());

    ExecutorService pool = Executors.newFixedThreadPool(64);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    IdempotentResult<String> result =
                        concurrent.execute(
                            KEY,
                            FP,
                            POLICY,
                            CODEC,
                            () -> {
                              executions.incrementAndGet();
                              // Hold ownership until every other caller has been answered.
                              assertThat(othersDone.await(30, TimeUnit.SECONDS)).isTrue();
                              return "once";
                            });
                    outcomes.add(result.outcome());
                  } catch (ZynqoException ex) {
                    rejections.add(ex.code());
                    othersDone.countDown();
                  } catch (Throwable unexpected) {
                    throw new AssertionError(unexpected);
                  }
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

    assertThat(executions).hasValue(1);
    assertThat(outcomes).containsExactly(Outcome.EXECUTED);
    assertThat(rejections).hasSize(callers - 1).containsOnly(ZynqoErrorCode.REQUEST_IN_PROGRESS);
  }

  private static final class RecordingObserver implements ZynqoObserver {
    final List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger executions = new AtomicInteger();
    final AtomicInteger failedExecutions = new AtomicInteger();
    final Map<StorageOperation, Integer> failedStorage =
        Collections.synchronizedMap(new EnumMap<>(StorageOperation.class));

    @Override
    public void onOutcome(Outcome outcome) {
      outcomes.add(outcome);
    }

    @Override
    public void onExecution(Duration duration, boolean success) {
      (success ? executions : failedExecutions).incrementAndGet();
    }

    @Override
    public void onStorageOperation(StorageOperation operation, Duration duration, boolean success) {
      if (!success) {
        failedStorage.merge(operation, 1, Integer::sum);
      }
    }
  }
}
