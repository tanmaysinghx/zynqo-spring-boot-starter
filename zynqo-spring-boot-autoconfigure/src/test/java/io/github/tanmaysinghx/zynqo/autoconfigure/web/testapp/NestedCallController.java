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
package io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp;

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Calls an {@code @Zynqo} method that is not the request's handler, which must be rejected. */
@RestController
public class NestedCallController {

  private final InnerService inner;

  public NestedCallController(InnerService inner) {
    this.inner = inner;
  }

  @PostMapping("/nested")
  public String nested() {
    return inner.work();
  }

  @Service
  public static class InnerService {
    @Zynqo
    public String work() {
      return "should not run";
    }
  }
}
