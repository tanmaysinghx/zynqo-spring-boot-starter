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

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order use cases. Transactions commit before Zynqo records completion. */
@Service
public class OrderService {

  private final OrderRepository repository;
  private final Clock clock;

  OrderService(OrderRepository repository) {
    this.repository = repository;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates an order.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if the owner already has an
   *     order with this reference (the business-level duplicate guard)
   */
  @Transactional
  public PurchaseOrder create(String owner, CreateOrderRequest request) {
    PurchaseOrder order =
        new PurchaseOrder(
            owner, request.reference(), request.sku(), request.quantity(), Instant.now(clock));
    return repository.saveAndFlush(order);
  }

  @Transactional(readOnly = true)
  public Optional<PurchaseOrder> find(String owner, UUID id) {
    return repository.findByIdAndOwner(id, owner);
  }

  @Transactional(readOnly = true)
  public long count(String owner) {
    return repository.countByOwner(owner);
  }
}
