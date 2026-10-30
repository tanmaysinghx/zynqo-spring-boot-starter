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

import java.util.OptionalLong;

/**
 * Exposes the fencing token of the idempotent execution running on the current thread.
 *
 * <p>Applications can store the fence alongside the data written by the operation and reject writes
 * carrying a lower fence. This protects downstream state from a stale worker that keeps running
 * after its lease was taken over, which the idempotency store alone cannot prevent.
 */
public final class ZynqoContext {

  private static final ThreadLocal<Long> FENCE = new ThreadLocal<>();

  private ZynqoContext() {}

  /**
   * Returns the fencing token of the execution running on this thread.
   *
   * @return the fence, or empty outside an idempotent execution
   */
  public static OptionalLong currentFencingToken() {
    Long fence = FENCE.get();
    return fence == null ? OptionalLong.empty() : OptionalLong.of(fence);
  }

  static Long enter(long fence) {
    Long previous = FENCE.get();
    FENCE.set(fence);
    return previous;
  }

  static void exit(Long previous) {
    if (previous == null) {
      FENCE.remove();
    } else {
      FENCE.set(previous);
    }
  }
}
