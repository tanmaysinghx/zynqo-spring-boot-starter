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
package io.github.tanmaysinghx.zynqo.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderCodecTest {

  @Test
  void roundTripsMultiValuedHeadersInOrder() {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Location", List.of("/orders/42"));
    headers.put("Content-Language", List.of("en", "de"));
    headers.put("X-Empty", List.of(""));
    headers.put("X-Colon", List.of("a:b:c"));

    Map<String, List<String>> decoded = HeaderCodec.decode(HeaderCodec.encode(headers));

    assertThat(decoded).containsExactlyEntriesOf(headers);
  }

  @Test
  void encodesNoHeadersAsEmpty() {
    assertThat(HeaderCodec.encode(Map.of())).isEmpty();
    assertThat(HeaderCodec.decode(new byte[0])).isEmpty();
  }

  @Test
  void rejectsHeadersThatWouldBreakTheFormat() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.encode(Map.of("Bad:Name", List.of("v"))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.encode(Map.of("", List.of("v"))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.encode(Map.of("X", List.of("a\nb"))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.encode(Map.of("X", List.of("a\rb"))));
  }

  @Test
  void rejectsMalformedEncodings() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.decode("X:1".getBytes(StandardCharsets.UTF_8)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.decode("novalue\n".getBytes(StandardCharsets.UTF_8)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HeaderCodec.decode(":v\n".getBytes(StandardCharsets.UTF_8)));
  }
}
