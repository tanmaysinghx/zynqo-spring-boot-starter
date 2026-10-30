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
package io.github.tanmaysinghx.zynqo.core;

import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;

/**
 * Converts between an operation's result and its storable representation.
 *
 * <p>Implementations must use a data-only format (for example JSON bound to a declared type) and
 * must never use Java native serialization or type information supplied by the stored data.
 *
 * @param <T> the result type
 */
public interface ResponseCodec<T> {

  /**
   * Encodes a result for storage.
   *
   * @param result the result, possibly {@code null}
   * @return the storable representation
   * @throws RuntimeException if the result cannot be encoded; the record is then marked failed
   */
  StoredResponse encode(T result);

  /**
   * Decodes a stored representation for replay.
   *
   * @param response the stored representation
   * @return the replayed result
   * @throws RuntimeException if the representation cannot be decoded
   */
  T decode(StoredResponse response);
}
