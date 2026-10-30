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
package io.github.tanmaysinghx.zynqo.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the example application on a real port against a real Redis server. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "demo.password=" + ExampleApplicationIT.PASSWORD)
@Testcontainers
class ExampleApplicationIT {

  static final String PASSWORD = "integration-test-only";

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  private static final JsonMapper JSON = JsonMapper.shared();

  @LocalServerPort int port;

  private RestClient http;
  private String key;
  private String reference;

  @BeforeEach
  void setUp() {
    http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    key = UUID.randomUUID().toString();
    reference = "ref-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String body(int quantity) {
    return "{\"reference\":\""
        + reference
        + "\",\"sku\":\"book-42\",\"quantity\":"
        + quantity
        + "}";
  }

  private ResponseEntity<String> create(String user, String idempotencyKey, String body) {
    var request =
        http.post()
            .uri("/api/orders")
            .header("Authorization", basic(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    if (idempotencyKey != null) {
      request = request.header("Idempotency-Key", idempotencyKey);
    }
    return request
        .retrieve()
        .onStatus(HttpStatusCode::isError, (req, res) -> {})
        .toEntity(String.class);
  }

  private static String basic(String user) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
  }

  private static JsonNode json(ResponseEntity<String> response) {
    return JSON.readTree(response.getBody());
  }

  @Test
  void duplicateRequestReplaysOriginalOrder() {
    ResponseEntity<String> first = create("alice", key, body(2));
    ResponseEntity<String> second = create("alice", key, body(2));

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(second.getStatusCode().value()).isEqualTo(201);
    assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
    assertThat(json(second).get("id")).isEqualTo(json(first).get("id"));

    ResponseEntity<String> fetched =
        http.get()
            .uri(first.getHeaders().getLocation())
            .header("Authorization", basic("alice"))
            .retrieve()
            .toEntity(String.class);
    assertThat(json(fetched).get("reference").asString()).isEqualTo(reference);
  }

  @Test
  void changedPayloadWithSameKeyIsRejected() {
    create("alice", key, body(2));

    ResponseEntity<String> conflict = create("alice", key, body(3));

    assertThat(conflict.getStatusCode().value()).isEqualTo(422);
    assertThat(json(conflict).get("code").asString()).isEqualTo("FINGERPRINT_MISMATCH");
  }

  @Test
  void usersAreIsolatedAndBusinessConstraintGuardsNewKeys() {
    ResponseEntity<String> alice = create("alice", key, body(2));
    ResponseEntity<String> bob = create("bob", key, body(2));
    ResponseEntity<String> aliceNewKey = create("alice", key + "-2", body(2));

    assertThat(bob.getStatusCode().value()).isEqualTo(201);
    assertThat(json(bob).get("id")).isNotEqualTo(json(alice).get("id"));
    // A new idempotency key is a new request; the unique (owner, reference) constraint rejects it.
    assertThat(aliceNewKey.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void validationFailureDoesNotConsumeTheKey() {
    ResponseEntity<String> invalid = create("alice", key, body(0));
    ResponseEntity<String> valid = create("alice", key, body(1));

    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(valid.getStatusCode().value()).isEqualTo(201);
    assertThat(valid.getHeaders().getFirst("Idempotent-Replayed")).isNull();
  }

  @Test
  void missingKeyIsRejected() {
    ResponseEntity<String> response = create("alice", null, body(1));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(json(response).get("code").asString()).isEqualTo("KEY_MISSING");
  }

  @Test
  void concurrentRequestsCreateExactlyOneOrder() throws Exception {
    int callers = 40;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return create("alice", key, body(1));
                }));
      }
      start.countDown();
      List<ResponseEntity<String>> responses = new ArrayList<>();
      for (Future<ResponseEntity<String>> future : futures) {
        responses.add(future.get(60, TimeUnit.SECONDS));
      }

      List<String> createdIds =
          responses.stream()
              .filter(r -> r.getStatusCode().value() == 201)
              .map(r -> json(r).get("id").asString())
              .distinct()
              .toList();
      assertThat(createdIds).hasSize(1);
      assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void healthIsPublic() {
    ResponseEntity<String> health =
        http.get().uri("/actuator/health").retrieve().toEntity(String.class);
    assertThat(json(health).get("status").asString()).isEqualTo("UP");
  }
}
