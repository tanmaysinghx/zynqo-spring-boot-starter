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
package io.github.tanmaysinghx.zynqo.autoconfigure;

/**
 * Signals that Zynqo cannot start safely because of an invalid configuration or an unsupported use
 * of {@code @Zynqo}. Reported at startup with an actionable message.
 */
public class ZynqoConfigurationException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final String action;

  /**
   * Creates an exception.
   *
   * @param description what is wrong
   * @param action how to fix it
   */
  public ZynqoConfigurationException(String description, String action) {
    super(description);
    this.action = action;
  }

  /**
   * Returns how to fix the problem.
   *
   * @return the suggested action
   */
  public String getAction() {
    return action;
  }
}
