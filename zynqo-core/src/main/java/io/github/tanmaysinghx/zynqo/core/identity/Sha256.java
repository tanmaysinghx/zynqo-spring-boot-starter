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
package io.github.tanmaysinghx.zynqo.core.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Unambiguous SHA-256 digest over a sequence of fields.
 *
 * <p>Every field is prefixed with its 4-byte big-endian length, so distinct field sequences can
 * never produce the same input bytes (for example {@code ("ab", "c")} versus {@code ("a", "bc")}).
 */
final class Sha256 {

  private final MessageDigest digest;

  Sha256(String domain) {
    try {
      this.digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is mandatory for every Java SE implementation.
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
    field(domain);
  }

  Sha256 field(String value) {
    return field(value.getBytes(StandardCharsets.UTF_8));
  }

  Sha256 field(byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
    return this;
  }

  String hex() {
    return HexFormat.of().formatHex(digest.digest());
  }
}
