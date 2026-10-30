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

import java.time.Instant;
import java.util.UUID;

/**
 * Order representation returned by the API.
 *
 * @param id order identifier
 * @param reference client order reference
 * @param sku product identifier
 * @param quantity number of items
 * @param createdAt creation time
 */
public record OrderResponse(
    UUID id, String reference, String sku, int quantity, Instant createdAt) {

  static OrderResponse of(PurchaseOrder order) {
    return new OrderResponse(
        order.getId(),
        order.getReference(),
        order.getSku(),
        order.getQuantity(),
        order.getCreatedAt());
  }
}
