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
package io.github.tanmaysinghx.zynqo.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp.OrderController;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp.ZynqoTestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP contract of {@code @Zynqo} endpoints against a real Redis server. */
@SpringBootTest(
    classes = ZynqoTestApplication.class,
    properties = {
      "spring.data.redis.timeout=500ms",
      "zynqo.response.max-body-size=1KB",
      "zynqo.storage.key-prefix=zynqo-http:"
    })
@AutoConfigureMockMvc
@Testcontainers
class ZynqoHttpContractIT {

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORDER = "{\"sku\":\"book\",\"quantity\":2}";

  @Autowired MockMvc mvc;
  @Autowired OrderController orders;
  @Autowired ZynqoTestApplication.Gate gate;
  @Autowired MeterRegistry meters;

  private String key;

  @BeforeEach
  void setUp() {
    key = UUID.randomUUID().toString();
    orders.executions().set(0);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    String token =
        Base64.getEncoder()
            .encodeToString(
                (user + ":" + ZynqoTestApplication.PASSWORD).getBytes(StandardCharsets.UTF_8));
    return request.header("Authorization", "Basic " + token);
  }

  private MockHttpServletRequestBuilder createOrder(
      String user, String idempotencyKey, String body) {
    MockHttpServletRequestBuilder request =
        as(user, post("/orders").contentType(MediaType.APPLICATION_JSON).content(body));
    return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
  }

  @Test
  void firstRequestExecutesAndDuplicateReplaysStatusBodyAndAllowListedHeaders() throws Exception {
    MvcResult first =
        mvc.perform(createOrder("alice", key, ORDER))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/orders/ord-1"))
            .andExpect(header().string("X-Request-Id", "per-request-ord-1"))
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.id").value("ord-1"))
            .andExpect(jsonPath("$.fence").value(1))
            .andReturn();

    mvc.perform(createOrder("alice", key, ORDER))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(header().string("Location", "/orders/ord-1"))
        // Headers outside the replay allow-list are regenerated, never replayed.
        .andExpect(header().doesNotExist("X-Request-Id"))
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(
            content().json(first.getResponse().getContentAsString(), JsonCompareMode.STRICT));

    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void canonicalisedPayloadWithDifferentFormattingIsTheSameRequest() throws Exception {
    mvc.perform(createOrder("alice", key, ORDER)).andExpect(status().isCreated());

    mvc.perform(createOrder("alice", key, "{ \"quantity\" : 2,\n \"sku\": \"book\" }"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"));

    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void sameKeyWithDifferentPayloadIsRejectedWithProblemDetails() throws Exception {
    mvc.perform(createOrder("alice", key, ORDER)).andExpect(status().isCreated());

    mvc.perform(createOrder("alice", key, "{\"sku\":\"book\",\"quantity\":3}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:zynqo:problem:fingerprint-mismatch"))
        .andExpect(jsonPath("$.code").value("FINGERPRINT_MISMATCH"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(
            jsonPath("$.detail")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(key))));

    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void missingKeyIsRejected() throws Exception {
    mvc.perform(createOrder("alice", null, ORDER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KEY_MISSING"));
    assertThat(orders.executions()).hasValue(0);
  }

  @Test
  void invalidOversizedAndRepeatedKeysAreRejected() throws Exception {
    mvc.perform(createOrder("alice", "has space", ORDER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KEY_INVALID"));
    mvc.perform(createOrder("alice", "k".repeat(256), ORDER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KEY_INVALID"));
    mvc.perform(createOrder("alice", key, ORDER).header("Idempotency-Key", "second"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KEY_INVALID"));
    assertThat(orders.executions()).hasValue(0);
  }

  @Test
  void sameKeyFromDifferentPrincipalsNeverReplaysAcrossUsers() throws Exception {
    mvc.perform(createOrder("alice", key, ORDER))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("ord-1"));

    mvc.perform(createOrder("bob", key, ORDER))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotent-Replayed"))
        .andExpect(jsonPath("$.id").value("ord-2"));
  }

  @Test
  void anonymousCallerIsRejectedWhenPrincipalIsRequired() throws Exception {
    mvc.perform(post("/public/subscribe").header("Idempotency-Key", key))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PRINCIPAL_REQUIRED"));
  }

  @Test
  void spelKeyFromRequestBody() throws Exception {
    String body = "{\"clientReference\":\"" + key + "\",\"sku\":\"pen\",\"quantity\":1}";
    MockHttpServletRequestBuilder request =
        as(
            "alice",
            post("/orders/by-reference").contentType(MediaType.APPLICATION_JSON).content(body));

    mvc.perform(request).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("ord-1"));
    mvc.perform(request)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.id").value("ord-1"));

    mvc.perform(
            as(
                "alice",
                post("/orders/by-reference")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sku\":\"pen\",\"quantity\":1}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KEY_MISSING"));
    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void spelKeyByArgumentIndex() throws Exception {
    mvc.perform(as("alice", post("/orders/by-index/" + key))).andExpect(status().isOk());
    mvc.perform(as("alice", post("/orders/by-index/" + key)))
        .andExpect(header().string("Idempotent-Replayed", "true"));
    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void voidHandlerReplaysStatusAndQueryParametersArePartOfTheFingerprint() throws Exception {
    mvc.perform(as("alice", post("/orders/o1/cancel?reason=late")).header("Idempotency-Key", key))
        .andExpect(status().isNoContent());
    mvc.perform(as("alice", post("/orders/o1/cancel?reason=late")).header("Idempotency-Key", key))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    mvc.perform(as("alice", post("/orders/o1/cancel?reason=other")).header("Idempotency-Key", key))
        .andExpect(status().isUnprocessableContent());
    mvc.perform(as("alice", post("/orders/o2/cancel?reason=late")).header("Idempotency-Key", key))
        .andExpect(status().isUnprocessableContent());
    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void genericCollectionAndMapResultsReplay() throws Exception {
    String batch = "[" + ORDER + "," + ORDER + "]";
    MockHttpServletRequestBuilder request =
        as("alice", post("/orders/batch").contentType(MediaType.APPLICATION_JSON).content(batch))
            .header("Idempotency-Key", key);
    String first = mvc.perform(request).andReturn().getResponse().getContentAsString();
    mvc.perform(request)
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(content().json(first, JsonCompareMode.STRICT));

    MockHttpServletRequestBuilder map =
        as(
                "alice",
                post("/orders/map")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"a\":[1,2.50]}"))
            .header("Idempotency-Key", key + "-map");
    mvc.perform(map).andExpect(jsonPath("$.echo.a[1]").value(2.5));
    mvc.perform(map)
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.echo.a[1]").value(2.5));
    assertThat(orders.executions()).hasValue(3);
  }

  @Test
  void responseEntityWithoutBodyReplaysStatusAndLocation() throws Exception {
    MockHttpServletRequestBuilder request =
        as("alice", post("/orders/empty")).header("Idempotency-Key", key);
    mvc.perform(request).andExpect(status().isAccepted());
    mvc.perform(request)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/orders/queue"))
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(content().string(""));
    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void applicationExceptionReleasesKeySoRetryExecutesAgain() throws Exception {
    MockHttpServletRequestBuilder request =
        as("alice", post("/orders/rejected").contentType(MediaType.APPLICATION_JSON).content(ORDER))
            .header("Idempotency-Key", key);

    mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    mvc.perform(request).andExpect(status().isBadRequest());

    assertThat(orders.executions()).hasValue(2);
  }

  @Test
  void concurrentDuplicateReceivesConflictWithRetryAfterAndLaterReplays() throws Exception {
    gate.arm();
    MockHttpServletRequestBuilder request =
        as("alice", post("/orders/slow").contentType(MediaType.APPLICATION_JSON).content(ORDER))
            .header("Idempotency-Key", key);
    CompletableFuture<MvcResult> first =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return mvc.perform(request).andReturn();
              } catch (Exception ex) {
                throw new IllegalStateException(ex);
              }
            });
    gate.awaitEntered();

    mvc.perform(request)
        .andExpect(status().isConflict())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));

    gate.open();
    assertThat(first.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void oversizedResponseIsReturnedButNotStoredAndKeyIsBlocked() throws Exception {
    MockHttpServletRequestBuilder request =
        as("alice", post("/orders/large")).header("Idempotency-Key", key);

    mvc.perform(request).andExpect(status().isOk()).andExpect(content().string("x".repeat(4096)));
    mvc.perform(request)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PREVIOUS_ATTEMPT_FAILED"));
    assertThat(orders.executions()).hasValue(1);
  }

  @Test
  void zynqoMethodCalledOutsideItsOwnHandlerIsRejected() {
    assertThatThrownBy(
            () -> mvc.perform(as("alice", post("/nested")).header("Idempotency-Key", key)))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not the Spring MVC handler");
  }

  @Test
  void outcomesArePublishedAsLowCardinalityMetrics() throws Exception {
    double executedBefore = count("executed");
    double replayedBefore = count("replayed");

    mvc.perform(createOrder("alice", key, ORDER));
    mvc.perform(createOrder("alice", key, ORDER));

    assertThat(count("executed")).isEqualTo(executedBefore + 1);
    assertThat(count("replayed")).isEqualTo(replayedBefore + 1);
    assertThat(meters.find("zynqo.storage.duration").tag("operation", "claim").timer()).isNotNull();
    assertThat(meters.find("zynqo.execution.duration").tag("result", "success").timer())
        .isNotNull();
    assertThat(meters.getMeters())
        .filteredOn(m -> m.getId().getName().startsWith("zynqo."))
        .allSatisfy(
            m ->
                assertThat(m.getId().getTags())
                    .extracting(t -> t.getKey())
                    .isSubsetOf("outcome", "operation", "result"));
  }

  private double count(String outcome) {
    var counter = meters.find("zynqo.requests").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
