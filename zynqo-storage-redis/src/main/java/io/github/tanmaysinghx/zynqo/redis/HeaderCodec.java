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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-based encoding of response headers: one {@code name:value} pair per line.
 *
 * <p>HTTP field names cannot contain {@code ':'} and field values cannot contain CR or LF (RFC
 * 9110), so the format is unambiguous for every valid header. Invalid headers are rejected rather
 * than escaped.
 */
final class HeaderCodec {

  private HeaderCodec() {}

  static byte[] encode(Map<String, List<String>> headers) {
    StringBuilder out = new StringBuilder();
    headers.forEach(
        (name, values) -> {
          if (name.isEmpty() || !isToken(name)) {
            throw new IllegalArgumentException("invalid header name");
          }
          for (String value : values) {
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
              throw new IllegalArgumentException("invalid value for header " + name);
            }
            out.append(name).append(':').append(value).append('\n');
          }
        });
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static Map<String, List<String>> decode(byte[] encoded) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    String text = new String(encoded, StandardCharsets.UTF_8);
    int start = 0;
    while (start < text.length()) {
      int end = text.indexOf('\n', start);
      if (end < 0) {
        throw new IllegalArgumentException("unterminated header line");
      }
      String line = text.substring(start, end);
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new IllegalArgumentException("malformed header line");
      }
      headers
          .computeIfAbsent(line.substring(0, colon), name -> new ArrayList<>())
          .add(line.substring(colon + 1));
      start = end + 1;
    }
    return headers;
  }

  /** RFC 9110 token characters. */
  private static boolean isToken(String name) {
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
      if (!ok) {
        return false;
      }
    }
    return true;
  }
}
