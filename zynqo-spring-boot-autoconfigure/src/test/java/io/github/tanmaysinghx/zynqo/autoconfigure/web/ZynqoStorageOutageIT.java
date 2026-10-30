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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp.OrderController;
import io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp.ZynqoTestApplication;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/** Fail-closed behaviour when Redis becomes unavailable after startup. */
@SpringBootTest(classes = ZynqoTestApplication.class)
@AutoConfigureMockMvc
class ZynqoStorageOutageIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void redis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.timeout", () -> "500ms");
    registry.add("spring.data.redis.connect-timeout", () -> "500ms");
  }

  @AfterAll
  static void stop() {
    REDIS.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired OrderController orders;

  @Test
  void requestsAreRefusedWithoutExecutingWhenRedisIsDown() throws Exception {
    String auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("alice:" + ZynqoTestApplication.PASSWORD).getBytes(StandardCharsets.UTF_8));
    REDIS.stop();
    int before = orders.executions().get();

    mvc.perform(
            post("/orders")
                .header("Authorization", auth)
                .header("Idempotency-Key", "outage-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"book\",\"quantity\":1}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));

    assertThat(orders.executions().get()).isEqualTo(before);
  }
}
