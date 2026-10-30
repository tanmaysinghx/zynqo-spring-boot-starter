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
package io.github.tanmaysinghx.zynqo.core.store;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, storable representation of a completed operation's result.
 *
 * <p>The encoding of {@code body} is defined by the {@link
 * io.github.tanmaysinghx.zynqo.core.ResponseCodec} that produced it.
 *
 * @param status the status code to replay, or {@code 0} when the result carries no status
 * @param headers response headers to replay, in insertion order; names must be RFC 9110 tokens and
 *     values must not contain CR, LF or NUL
 * @param body the encoded body, or {@code null} when the result has no body
 */
public record StoredResponse(int status, Map<String, List<String>> headers, byte[] body) {

  /** Validates and defensively copies the components. */
  public StoredResponse {
    if (status != 0 && (status < 100 || status > 599)) {
      throw new IllegalArgumentException("status must be 0 or a valid HTTP status code");
    }
    Objects.requireNonNull(headers, "headers");
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          if (!isFieldName(name)) {
            throw new IllegalArgumentException("header name is not a valid HTTP field name");
          }
          for (String value : values) {
            if (!isFieldValue(value)) {
              throw new IllegalArgumentException("header value contains CR, LF or NUL");
            }
          }
          copy.put(name, List.copyOf(values));
        });
    headers = Collections.unmodifiableMap(copy);
    body = body == null ? null : body.clone();
  }

  /**
   * Returns a copy of the encoded body.
   *
   * @return the body bytes, or {@code null}
   */
  @Override
  public byte[] body() {
    return body == null ? null : body.clone();
  }

  /**
   * Returns the size of the encoded body in bytes.
   *
   * @return the body size, {@code 0} when there is no body
   */
  public int bodySize() {
    return body == null ? 0 : body.length;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StoredResponse that
        && status == that.status
        && headers.equals(that.headers)
        && Arrays.equals(body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, headers, Arrays.hashCode(body));
  }

  private static boolean isFieldName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean token =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
      if (!token) {
        return false;
      }
    }
    return true;
  }

  private static boolean isFieldValue(String value) {
    return value != null
        && value.indexOf('\r') < 0
        && value.indexOf('\n') < 0
        && value.indexOf('\0') < 0;
  }

  /** Response bodies may contain personal data; print their size only. */
  @Override
  public String toString() {
    return "StoredResponse[status="
        + status
        + ", headers="
        + headers.keySet()
        + ", bodyBytes="
        + (body == null ? "none" : body.length)
        + "]";
  }
}
