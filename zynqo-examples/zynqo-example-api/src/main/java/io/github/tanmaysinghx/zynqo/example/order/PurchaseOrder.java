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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * An order. The unique constraint on (owner, reference) is the business-level guarantee that backs
 * Zynqo: even if an idempotency lease expired while a slow request was still running, the database
 * rejects a second order with the same client reference.
 */
@Entity
@Table(
    name = "purchase_order",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_order_owner_reference",
            columnNames = {"owner", "reference"}))
public class PurchaseOrder {

  @Id private UUID id;

  @Column(nullable = false, length = 100)
  private String owner;

  @Column(nullable = false, length = 64)
  private String reference;

  @Column(nullable = false, length = 64)
  private String sku;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false)
  private Instant createdAt;

  protected PurchaseOrder() {}

  PurchaseOrder(String owner, String reference, String sku, int quantity, Instant createdAt) {
    this.id = UUID.randomUUID();
    this.owner = owner;
    this.reference = reference;
    this.sku = sku;
    this.quantity = quantity;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getOwner() {
    return owner;
  }

  public String getReference() {
    return reference;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
