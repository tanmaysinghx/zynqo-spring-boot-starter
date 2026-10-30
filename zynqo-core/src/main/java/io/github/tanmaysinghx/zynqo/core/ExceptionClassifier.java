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

import java.util.Objects;

/**
 * Decides the {@link ExceptionDisposition} for a failure of the protected operation.
 *
 * <p>Applications that know which exceptions occur before or after side effects should provide
 * their own implementation.
 */
@FunctionalInterface
public interface ExceptionClassifier {

  /**
   * Classifies a failure.
   *
   * @param failure the exception thrown by the protected operation
   * @return how the idempotency record is handled
   */
  ExceptionDisposition classify(Throwable failure);

  /**
   * Returns a classifier that applies the same disposition to every failure.
   *
   * @param disposition the disposition
   * @return the classifier
   */
  static ExceptionClassifier always(ExceptionDisposition disposition) {
    Objects.requireNonNull(disposition, "disposition");
    return failure -> disposition;
  }
}
