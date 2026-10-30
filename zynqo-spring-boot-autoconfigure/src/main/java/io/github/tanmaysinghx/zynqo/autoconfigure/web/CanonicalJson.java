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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.POJONode;

/**
 * Deterministic JSON serialization used for fingerprinting.
 *
 * <ul>
 *   <li>object members are sorted by name (UTF-16 code unit order) at every level;
 *   <li>no insignificant whitespace;
 *   <li>numbers are written in plain decimal notation without trailing zeros, so {@code 1}, {@code
 *       1.0} and {@code 1.00} are equal;
 *   <li>strings use the minimal JSON escaping ({@code "}, {@code \}, and control characters);
 *   <li>binary values are written as Base64 strings.
 * </ul>
 *
 * <p>The output depends only on the JSON tree, not on mapper configuration such as indentation.
 */
final class CanonicalJson {

  private CanonicalJson() {}

  static String write(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  private static void write(JsonNode node, StringBuilder out) {
    switch (node.getNodeType()) {
      case OBJECT -> {
        List<Map.Entry<String, JsonNode>> members = new ArrayList<>(node.properties());
        members.sort(Map.Entry.comparingByKey());
        out.append('{');
        for (int i = 0; i < members.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          string(members.get(i).getKey(), out);
          out.append(':');
          write(members.get(i).getValue(), out);
        }
        out.append('}');
      }
      case ARRAY -> {
        out.append('[');
        int i = 0;
        for (JsonNode element : node.values()) {
          if (i++ > 0) {
            out.append(',');
          }
          write(element, out);
        }
        out.append(']');
      }
      case STRING -> string(node.stringValue(), out);
      case NUMBER -> out.append(number(node));
      case BOOLEAN -> out.append(node.booleanValue());
      case NULL, MISSING -> out.append("null");
      case BINARY -> string(Base64.getEncoder().encodeToString(node.binaryValue()), out);
      case POJO -> {
        // Binary values produced by valueToTree are embedded as POJO nodes holding byte[].
        if (node instanceof POJONode pojo && pojo.getPojo() instanceof byte[] bytes) {
          string(Base64.getEncoder().encodeToString(bytes), out);
        } else {
          throw new IllegalArgumentException(
              "value of type POJO cannot be represented canonically; register a serializer");
        }
      }
    }
  }

  private static String number(JsonNode node) {
    if (node.isIntegralNumber()) {
      return node.bigIntegerValue().toString();
    }
    BigDecimal decimal = node.decimalValue().stripTrailingZeros();
    return decimal.signum() == 0 ? "0" : decimal.toPlainString();
  }

  private static void string(String value, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
