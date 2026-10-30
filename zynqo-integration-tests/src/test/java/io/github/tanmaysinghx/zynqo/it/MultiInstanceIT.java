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
package io.github.tanmaysinghx.zynqo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.tanmaysinghx.zynqo.it.ClusterApplication.Execution;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Several independent application instances (separate Spring contexts, servers and Redis connection
 * pools) sharing one real Redis server, exercised over real HTTP.
 */
class MultiInstanceIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
  private static final JsonMapper JSON = JsonMapper.shared();
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  private static final List<ConfigurableApplicationContext> INSTANCES = new ArrayList<>();
  private static Instance a;
  private static Instance b;
  private static Instance c;

  private String key;
  private String reference;

  record Instance(String name, ConfigurableApplicationContext context, int port) {
    ClusterApplication.Gate gate() {
      return context.getBean(ClusterApplication.Gate.class);
    }
  }

  static Instance start(String name) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ClusterApplication.class)
            .properties(
                "server.port=0",
                "server.shutdown=immediate",
                "test.instance=" + name,
                "spring.data.redis.host=" + REDIS.getHost(),
                "spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "spring.data.redis.timeout=1s",
                "zynqo.storage.key-prefix=zynqo-it:",
                "zynqo.execution.lease-duration=30s")
            .run();
    INSTANCES.add(context);
    int port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    return new Instance(name, context, port);
  }

  @BeforeAll
  static void startCluster() {
    REDIS.start();
    a = start("a");
    b = start("b");
    c = start("c");
  }

  @AfterAll
  static void stopCluster() {
    INSTANCES.forEach(ConfigurableApplicationContext::close);
    REDIS.stop();
  }

  @BeforeEach
  void setUp() {
    key = UUID.randomUUID().toString();
    reference = "pay-" + UUID.randomUUID();
    ClusterApplication.EXECUTIONS.clear();
    ClusterApplication.HOLD.set(null);
  }

  private HttpResponse<String> post(Instance instance, String path, String user, String body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + instance.port() + path))
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .header("X-Test-User", user)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String payment(long amount) {
    return "{\"reference\":\"" + reference + "\",\"amountCents\":" + amount + "}";
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  private List<Execution> executions() {
    return ClusterApplication.EXECUTIONS.stream()
        .filter(e -> e.reference().equals(reference))
        .toList();
  }

  @Test
  void completedRequestReplaysOnEveryInstance() throws Exception {
    HttpResponse<String> first = post(a, "/payments", "alice", payment(500));
    HttpResponse<String> onB = post(b, "/payments", "alice", payment(500));
    HttpResponse<String> onC = post(c, "/payments", "alice", payment(500));

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(onB.statusCode()).isEqualTo(201);
    assertThat(onB.headers().firstValue("Idempotent-Replayed")).contains("true");
    assertThat(onC.headers().firstValue("Location"))
        .isEqualTo(first.headers().firstValue("Location"));
    assertThat(json(onC)).isEqualTo(json(first));
    assertThat(json(onC).get("instance").asString()).isEqualTo("a");
    assertThat(executions()).hasSize(1);
  }

  @Test
  void conflictingPayloadIsRejectedOnAnotherInstance() throws Exception {
    post(a, "/payments", "alice", payment(500));

    HttpResponse<String> conflict = post(b, "/payments", "alice", payment(501));

    assertThat(conflict.statusCode()).isEqualTo(422);
    assertThat(json(conflict).get("code").asString()).isEqualTo("FINGERPRINT_MISMATCH");
    assertThat(executions()).hasSize(1);
  }

  @Test
  void hundredsOfConcurrentDuplicatesAcrossInstancesExecuteExactlyOnce() throws Exception {
    int callers = 300;
    List<Instance> cluster = List.of(a, b, c);
    CountDownLatch start = new CountDownLatch(1);
    // The owner stays inside the handler until every other caller has been answered, so all
    // duplicates observe the in-progress state; no timing assumptions are involved.
    CountDownLatch othersAnswered = new CountDownLatch(callers - 1);
    ClusterApplication.HOLD.set(othersAnswered);
    ExecutorService pool = Executors.newFixedThreadPool(100);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < callers; i++) {
        Instance target = cluster.get(i % cluster.size());
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  HttpResponse<String> response = post(target, "/payments", "alice", payment(900));
                  if (response.statusCode() != 201) {
                    othersAnswered.countDown();
                  }
                  return response;
                }));
      }
      start.countDown();
      List<HttpResponse<String>> responses = new ArrayList<>();
      for (Future<HttpResponse<String>> future : futures) {
        responses.add(future.get(120, TimeUnit.SECONDS));
      }

      assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
      assertThat(responses)
          .filteredOn(r -> r.statusCode() == 409)
          .hasSize(callers - 1)
          .allSatisfy(
              r -> {
                assertThat(r.headers().firstValue("Retry-After")).isPresent();
                assertThat(json(r).get("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
              });
    } finally {
      ClusterApplication.HOLD.set(null);
      pool.shutdownNow();
    }
    assertThat(executions()).hasSize(1);

    HttpResponse<String> after = post(b, "/payments", "alice", payment(900));
    assertThat(after.headers().firstValue("Idempotent-Replayed")).contains("true");
    assertThat(executions()).hasSize(1);
  }

  @Test
  void staleOwnerCannotOverwriteTheResultOfTheOwnerThatTookOver() throws Exception {
    // Instance a starts executing and stalls past its 1s lease (GC pause, slow dependency, ...).
    CompletableFuture<HttpResponse<String>> stalled =
        CompletableFuture.supplyAsync(
            () -> unchecked(() -> post(a, "/payments/slow", "alice", payment(700))));
    assertThat(a.gate().awaitEntered()).isTrue();

    // While the lease is valid, duplicates are rejected; once it expires, b takes over.
    b.gate().open();
    HttpResponse<String> takeover =
        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(100))
            .until(
                () -> post(b, "/payments/slow", "alice", payment(700)), r -> r.statusCode() == 200);
    assertThat(json(takeover).get("instance").asString()).isEqualTo("b");
    assertThat(json(takeover).get("fence").asLong()).isEqualTo(2);

    // The stale owner finishes later. Its caller gets its real result, but it cannot overwrite b's.
    a.gate().open();
    HttpResponse<String> stale = stalled.get(30, TimeUnit.SECONDS);
    assertThat(stale.statusCode()).isEqualTo(200);
    assertThat(json(stale).get("fence").asLong()).isEqualTo(1);

    HttpResponse<String> replay = post(c, "/payments/slow", "alice", payment(700));
    assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
    assertThat(json(replay).get("instance").asString()).isEqualTo("b");
    assertThat(json(replay).get("fence").asLong()).isEqualTo(2);

    // Documented limitation: after lease expiry the operation ran twice. Fencing tokens (1, 2)
    // let downstream systems reject the stale write.
    assertThat(executions()).extracting(Execution::fence).containsExactlyInAnyOrder(1L, 2L);
  }

  @Test
  void shutdownInterruptingAnInFlightRequestReleasesItsClaim() throws Exception {
    Instance doomed = start("doomed-shutdown");
    CompletableFuture<?> inFlight =
        CompletableFuture.runAsync(
            () -> {
              try {
                post(doomed, "/payments/slow", "alice", payment(300));
              } catch (Exception expected) {
                // The connection is cut when the instance stops.
              }
            });
    assertThat(doomed.gate().awaitEntered()).isTrue();

    // Immediate shutdown interrupts the handler thread. The handler fails before its side effect,
    // and the default RELEASE disposition deletes the claim while Redis is still reachable.
    doomed.context().close();
    INSTANCES.remove(doomed.context());
    inFlight.get(60, TimeUnit.SECONDS);

    b.gate().open();
    HttpResponse<String> retried = post(b, "/payments/slow", "alice", payment(300));
    assertThat(retried.statusCode()).isEqualTo(200);
    assertThat(json(retried).get("instance").asString()).isEqualTo("b");
    assertThat(json(retried).get("fence").asLong()).isEqualTo(1);
    assertThat(executions()).extracting(Execution::instance).containsExactly("b");
  }

  @Test
  void ownerThatLosesRedisMidExecutionIsTakenOverAfterLeaseExpiry() throws Exception {
    Instance partitioned = start("partitioned");
    CompletableFuture<HttpResponse<String>> inFlight =
        CompletableFuture.supplyAsync(
            () -> unchecked(() -> post(partitioned, "/payments/slow", "alice", payment(400))));
    assertThat(partitioned.gate().awaitEntered()).isTrue();

    // The owner loses its Redis connectivity while executing (partition, pool shutdown, crash of
    // its sidecar): it can neither complete nor release, so its claim stays until the lease ends.
    partitioned.context().getBean(LettuceConnectionFactory.class).destroy();
    partitioned.gate().open();
    HttpResponse<String> ownerResponse = inFlight.get(30, TimeUnit.SECONDS);
    // The operation ran; its caller receives the real result even though it could not be stored.
    assertThat(ownerResponse.statusCode()).isEqualTo(200);
    assertThat(json(ownerResponse).get("fence").asLong()).isEqualTo(1);

    b.gate().open();
    HttpResponse<String> recovered =
        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(100))
            .until(
                () -> post(b, "/payments/slow", "alice", payment(400)), r -> r.statusCode() == 200);
    assertThat(json(recovered).get("instance").asString()).isEqualTo("b");
    assertThat(json(recovered).get("fence").asLong()).isEqualTo(2);

    HttpResponse<String> replay = post(a, "/payments/slow", "alice", payment(400));
    assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
    assertThat(json(replay).get("instance").asString()).isEqualTo("b");

    // Documented limitation: the operation executed twice (fences 1 and 2). This is the case that
    // requires business-level guards such as unique constraints or fencing.
    assertThat(executions()).extracting(Execution::fence).containsExactlyInAnyOrder(1L, 2L);
  }

  @Test
  void keysAreIsolatedBetweenPrincipalsAcrossInstances() throws Exception {
    HttpResponse<String> alice = post(a, "/payments", "alice", payment(100));
    HttpResponse<String> bob = post(b, "/payments", "bob", payment(100));

    assertThat(alice.statusCode()).isEqualTo(201);
    assertThat(bob.statusCode()).isEqualTo(201);
    assertThat(bob.headers().firstValue("Idempotent-Replayed")).isEmpty();
    assertThat(executions()).hasSize(2);
  }

  private interface IoCall<T> {
    T call() throws Exception;
  }

  private static <T> T unchecked(IoCall<T> call) {
    try {
      return call.call();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
