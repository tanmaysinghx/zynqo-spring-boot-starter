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
package io.github.tanmaysinghx.zynqo.example.order;

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

  private final OrderService orders;

  OrderController(OrderService orders) {
    this.orders = orders;
  }

  /**
   * Creates an order. Retrying with the same {@code Idempotency-Key} header and the same body
   * returns the original response; a different body with the same key is rejected with 422.
   */
  @Zynqo
  @PostMapping
  public ResponseEntity<OrderResponse> create(
      @Valid @RequestBody CreateOrderRequest request, Principal principal) {
    PurchaseOrder order = orders.create(principal.getName(), request);
    return ResponseEntity.created(URI.create("/api/orders/" + order.getId()))
        .body(OrderResponse.of(order));
  }

  @GetMapping("/{id}")
  public ResponseEntity<OrderResponse> get(@PathVariable UUID id, Principal principal) {
    return orders
        .find(principal.getName(), id)
        .map(order -> ResponseEntity.ok(OrderResponse.of(order)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** The business duplicate guard: a second order with the same reference is a conflict. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail duplicateReference(DataIntegrityViolationException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "An order with this reference already exists.");
    problem.setType(URI.create("urn:example:problem:duplicate-order-reference"));
    return problem;
  }
}
