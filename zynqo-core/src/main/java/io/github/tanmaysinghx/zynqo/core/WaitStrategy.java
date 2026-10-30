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

import java.time.Duration;

/** Pauses the calling thread between checks while waiting for an in-progress duplicate. */
@FunctionalInterface
public interface WaitStrategy {

  /** Sleeps the calling thread. */
  WaitStrategy SLEEP =
      duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000);

  /**
   * Pauses for at most the given duration.
   *
   * @param duration the maximum pause
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  void pause(Duration duration) throws InterruptedException;
}
