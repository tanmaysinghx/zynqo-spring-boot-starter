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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import io.github.tanmaysinghx.zynqo.core.store.ClaimResult;
import io.github.tanmaysinghx.zynqo.core.store.OwnershipToken;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTest {

  private static ExecutionPolicy policy(Duration lease, Duration retention) {
    return new ExecutionPolicy(
        lease, retention, retention, Duration.ZERO, Duration.ofMillis(10), 1);
  }

  @Test
  void policyRejectsNonPositiveDurations() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> policy(Duration.ZERO, Duration.ofHours(1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> policy(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ExecutionPolicy(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(-1),
                    Duration.ofMillis(10),
                    1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ExecutionPolicy(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ZERO,
                    Duration.ofMillis(10),
                    0));
    assertThatThrownBy(() -> policy(null, Duration.ofHours(1)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void inProgressRecordTtlIsNeverShorterThanLease() {
    assertThat(policy(Duration.ofMinutes(5), Duration.ofMinutes(1)).inProgressRecordTtl())
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(policy(Duration.ofMinutes(1), Duration.ofHours(1)).inProgressRecordTtl())
        .isEqualTo(Duration.ofHours(1));
  }

  @Test
  void policyCopiesReplaceOnlyTheirComponent() {
    ExecutionPolicy base = policy(Duration.ofMinutes(1), Duration.ofHours(1));

    assertThat(base.withLease(Duration.ofMinutes(2)))
        .isEqualTo(policy(Duration.ofMinutes(2), Duration.ofHours(1)));
    assertThat(base.withCompletedRetention(Duration.ofHours(3)).completedRetention())
        .isEqualTo(Duration.ofHours(3));
    assertThat(base.withCompletedRetention(Duration.ofHours(3)).failedRetention())
        .isEqualTo(Duration.ofHours(1));
  }

  @Test
  void storedResponseIsImmutableAndValueBased() {
    byte[] body = {1, 2, 3};
    List<String> values = new ArrayList<>(List.of("a"));
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("X", values);
    StoredResponse response = new StoredResponse(200, headers, body);

    body[0] = 9;
    values.add("b");
    headers.put("Y", List.of());
    response.body()[1] = 9;

    assertThat(response.body()).containsExactly(1, 2, 3);
    assertThat(response.headers()).containsExactly(Map.entry("X", List.of("a")));
    assertThat(response.bodySize()).isEqualTo(3);
    assertThat(response)
        .isEqualTo(new StoredResponse(200, Map.of("X", List.of("a")), new byte[] {1, 2, 3}));
    assertThat(response)
        .hasSameHashCodeAs(
            new StoredResponse(200, Map.of("X", List.of("a")), new byte[] {1, 2, 3}));
    assertThat(response)
        .isNotEqualTo(new StoredResponse(201, Map.of("X", List.of("a")), new byte[] {1, 2, 3}));
    assertThat(response.toString()).contains("bodyBytes=3").doesNotContain("[1, 2, 3]");
    assertThatThrownBy(() -> response.headers().put("Z", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void storedResponseSupportsMissingBodyAndStatus() {
    StoredResponse response = new StoredResponse(0, Map.of(), null);

    assertThat(response.body()).isNull();
    assertThat(response.bodySize()).isZero();
    assertThat(response.toString()).contains("bodyBytes=none");
    assertThatIllegalArgumentException().isThrownBy(() -> new StoredResponse(42, Map.of(), null));
    assertThatIllegalArgumentException().isThrownBy(() -> new StoredResponse(600, Map.of(), null));
  }

  @Test
  void ownershipTokenValidatesAndHidesOwner() {
    OwnershipToken token = new OwnershipToken("secret-owner", 3);

    assertThat(token.toString()).isEqualTo("OwnershipToken[fence=3]");
    assertThatIllegalArgumentException().isThrownBy(() -> new OwnershipToken(" ", 1));
    assertThatIllegalArgumentException().isThrownBy(() -> new OwnershipToken("o", 0));
  }

  @Test
  void claimResultsValidateComponents() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ClaimResult.InProgress(Duration.ofMillis(-1)));
    assertThatThrownBy(() -> new ClaimResult.Acquired(null, false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClaimResult.Completed(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void exceptionCarriesCodeAndRetryGuidance() {
    ZynqoException plain = new ZynqoException(ZynqoErrorCode.KEY_MISSING, "missing");
    ZynqoException retry =
        new ZynqoException(ZynqoErrorCode.REQUEST_IN_PROGRESS, "busy", Duration.ofSeconds(2), null);

    assertThat(plain.code()).isEqualTo(ZynqoErrorCode.KEY_MISSING);
    assertThat(plain.retryAfter()).isEmpty();
    assertThat(retry.retryAfter()).contains(Duration.ofSeconds(2));
  }

  @Test
  void storageOperationTagValuesAreLowercase() {
    assertThat(StorageOperation.CLAIM.tagValue()).isEqualTo("claim");
    assertThat(StorageOperation.COMPLETE.tagValue()).isEqualTo("complete");
  }

  @Test
  void resultReportsReplay() {
    assertThat(new IdempotentResult<>("x", Outcome.REPLAYED).replayed()).isTrue();
    assertThat(new IdempotentResult<>("x", Outcome.EXECUTED).replayed()).isFalse();
    assertThatThrownBy(() -> new IdempotentResult<>("x", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void sleepWaitStrategyPausesForRequestedDuration() throws InterruptedException {
    long start = System.nanoTime();

    WaitStrategy.SLEEP.pause(Duration.ofMillis(5));

    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(5).toNanos());
  }

  @Test
  void storedResponseRejectsInvalidHeaders() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StoredResponse(200, Map.of("Bad Name", List.of("v")), null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StoredResponse(200, Map.of("", List.of("v")), null));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new StoredResponse(200, Map.of("X", List.of("a\r\nSet-Cookie: x")), null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StoredResponse(200, Map.of("X", List.of("a\u0000")), null));
    assertThat(
            new StoredResponse(200, Map.of("X-Trace_id.1", List.of("a b;c=\"d\"")), null).headers())
        .containsKey("X-Trace_id.1");
  }
}
