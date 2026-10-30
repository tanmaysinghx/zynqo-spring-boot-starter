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
package io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp;

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import io.github.tanmaysinghx.zynqo.core.ZynqoContext;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

  public record OrderRequest(String clientReference, String sku, int quantity) {}

  public record OrderResponse(String id, String sku, int quantity, long fence) {}

  private final AtomicInteger executions = new AtomicInteger();
  private final ZynqoTestApplication.Gate gate;

  public OrderController(ZynqoTestApplication.Gate gate) {
    this.gate = gate;
  }

  /** Accessed through a method: fields of a CGLIB proxy are not the target's fields. */
  public AtomicInteger executions() {
    return executions;
  }

  private OrderResponse newOrder(OrderRequest request) {
    int id = executions.incrementAndGet();
    return new OrderResponse(
        "ord-" + id,
        request.sku(),
        request.quantity(),
        ZynqoContext.currentFencingToken().orElse(-1));
  }

  @Zynqo
  @PostMapping
  public ResponseEntity<OrderResponse> create(@RequestBody OrderRequest request) {
    OrderResponse order = newOrder(request);
    return ResponseEntity.created(URI.create("/orders/" + order.id()))
        .header("X-Request-Id", "per-request-" + order.id())
        .header("Set-Cookie", "session=secret")
        .body(order);
  }

  @Zynqo(key = "#request.clientReference")
  @PostMapping("/by-reference")
  @ResponseStatus(HttpStatus.CREATED)
  public OrderResponse createByReference(@RequestBody OrderRequest request) {
    return newOrder(request);
  }

  @Zynqo(key = "#p0")
  @PostMapping("/by-index/{reference}")
  public OrderResponse createByIndex(@PathVariable String reference) {
    return newOrder(new OrderRequest(reference, "sku", 1));
  }

  @Zynqo(operation = "orders.cancel", retention = "PT10M", lease = "30s")
  @PostMapping("/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable String id, @RequestParam(defaultValue = "none") String reason) {
    executions.incrementAndGet();
  }

  @Zynqo
  @PostMapping("/batch")
  public List<OrderResponse> batch(@RequestBody List<OrderRequest> requests) {
    return requests.stream().map(this::newOrder).toList();
  }

  @Zynqo
  @PostMapping("/empty")
  public ResponseEntity<Void> empty() {
    executions.incrementAndGet();
    return ResponseEntity.accepted().header("Location", "/orders/queue").build();
  }

  @Zynqo
  @PostMapping("/map")
  public Map<String, Object> map(@RequestBody Map<String, Object> body) {
    executions.incrementAndGet();
    return Map.of("echo", body);
  }

  @Zynqo
  @PostMapping("/rejected")
  public OrderResponse rejected(@RequestBody OrderRequest request) {
    executions.incrementAndGet();
    throw new IllegalArgumentException("quantity must be positive");
  }

  @Zynqo
  @PostMapping("/slow")
  public OrderResponse slow(@RequestBody OrderRequest request) throws InterruptedException {
    gate.pass();
    return newOrder(request);
  }

  @Zynqo
  @PostMapping("/large")
  public String large() {
    executions.incrementAndGet();
    return "x".repeat(4096);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
  }
}
