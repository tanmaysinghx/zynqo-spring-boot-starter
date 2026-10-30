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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to create an order.
 *
 * @param reference client-chosen order reference, unique per customer
 * @param sku product identifier
 * @param quantity number of items
 */
public record CreateOrderRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String reference,
    @NotBlank @Size(max = 64) String sku,
    @Min(1) @Max(1000) int quantity) {}
