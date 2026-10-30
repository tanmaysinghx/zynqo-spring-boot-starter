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

import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;

/**
 * Computes the fingerprint that detects reuse of an idempotency key with a different request.
 *
 * <p>The default implementation hashes the HTTP method, the operation and a canonical JSON form of
 * the {@code @RequestBody}, {@code @PathVariable}, {@code @RequestParam} and
 * {@code @ModelAttribute} arguments (and unannotated simple-type arguments). Declare a bean of this
 * type to replace it.
 */
@FunctionalInterface
public interface ZynqoRequestFingerprinter {

  /**
   * Computes the fingerprint of an invocation.
   *
   * @param invocation the intercepted invocation
   * @return the fingerprint
   */
  RequestFingerprint fingerprint(ZynqoInvocationContext invocation);
}
