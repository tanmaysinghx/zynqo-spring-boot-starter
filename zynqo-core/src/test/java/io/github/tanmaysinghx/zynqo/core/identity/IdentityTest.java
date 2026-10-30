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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityTest {

  private static final IdempotencyScope SCOPE = new IdempotencyScope("acme", "alice", "POST /o");

  @Test
  void derivedKeyIsDeterministicLowercaseHex() {
    IdempotencyKey a = IdempotencyKey.derive(SCOPE, "key-1");
    IdempotencyKey b = IdempotencyKey.derive(SCOPE, "key-1");

    assertThat(a).isEqualTo(b);
    assertThat(a.id()).matches("[0-9a-f]{64}");
  }

  @Test
  void everyScopeComponentSeparatesKeys() {
    IdempotencyKey base = IdempotencyKey.derive(SCOPE, "key-1");

    assertThat(IdempotencyKey.derive(new IdempotencyScope("other", "alice", "POST /o"), "key-1"))
        .isNotEqualTo(base);
    assertThat(IdempotencyKey.derive(new IdempotencyScope("acme", "bob", "POST /o"), "key-1"))
        .isNotEqualTo(base);
    assertThat(IdempotencyKey.derive(SCOPE.withOperation("POST /p"), "key-1")).isNotEqualTo(base);
    assertThat(IdempotencyKey.derive(SCOPE, "key-2")).isNotEqualTo(base);
  }

  @Test
  void componentBoundariesCannotBeShifted() {
    // Without length prefixes these would concatenate to the same input.
    IdempotencyKey a = IdempotencyKey.derive(new IdempotencyScope("ab", "c", "op"), "k");
    IdempotencyKey b = IdempotencyKey.derive(new IdempotencyScope("a", "bc", "op"), "k");

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void identitiesDoNotLeakSensitiveValuesThroughToString() {
    IdempotencyKey key = IdempotencyKey.derive(SCOPE, "secret-client-key");
    RequestFingerprint fp = RequestFingerprint.builder().add("body", "{\"card\":1}").build();

    assertThat(key.toString()).doesNotContain(key.id()).doesNotContain("secret");
    assertThat(fp.toString()).doesNotContain(fp.value()).doesNotContain("card");
    assertThat(SCOPE.toString()).doesNotContain("acme").doesNotContain("alice");
  }

  @Test
  void keyAndFingerprintRejectMalformedDigests() {
    assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey("ABC"));
    assertThatIllegalArgumentException().isThrownBy(() -> new RequestFingerprint("xyz"));
  }

  @Test
  void scopeRequiresOperation() {
    assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyScope("", "", " "));
    assertThatThrownBy(() -> new IdempotencyScope(null, "", "op"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void fingerprintDependsOnNamesValuesAndOrder() {
    RequestFingerprint base = RequestFingerprint.builder().add("a", "1").add("b", "2").build();

    assertThat(RequestFingerprint.builder().add("a", "1").add("b", "2").build()).isEqualTo(base);
    assertThat(RequestFingerprint.builder().add("b", "2").add("a", "1").build()).isNotEqualTo(base);
    assertThat(RequestFingerprint.builder().add("a", "1").add("c", "2").build()).isNotEqualTo(base);
    assertThat(
            RequestFingerprint.builder()
                .add("a", "1")
                .add("b", "2".getBytes(StandardCharsets.UTF_8))
                .build())
        .isEqualTo(base);
  }

  @Test
  void fingerprintBuilderIsSingleUse() {
    RequestFingerprint.Builder builder = RequestFingerprint.builder().add("a", "1");
    builder.build();

    assertThatIllegalStateException().isThrownBy(builder::build);
    assertThatIllegalStateException().isThrownBy(() -> builder.add("b", "2"));
  }

  @Test
  void validatorAcceptsCommonKeyFormats() {
    ClientKeyValidator validator = new ClientKeyValidator(64);

    assertThat(validator.validate("8e0f7a52-4b4f-4f0e-9c5e-3f1f6f0b1a2c"))
        .isEqualTo("8e0f7a52-4b4f-4f0e-9c5e-3f1f6f0b1a2c");
    assertThat(validator.validate("01HZX3K6Q9V8R7T6Y5U4I3O2P1")).isNotNull();
    assertThat(validator.validate("~!order:42/retry#1")).isNotNull();
    assertThat(validator.validate("x".repeat(64))).hasSize(64);
    assertThat(validator.maxLength()).isEqualTo(64);
  }

  @ParameterizedTest
  @ValueSource(strings = {"has space", "tab\tkey", "new\nline", "ünïcode", "\u007f"})
  void validatorRejectsInvalidCharacters(String key) {
    assertThatThrownBy(() -> new ClientKeyValidator(64).validate(key))
        .isInstanceOfSatisfying(
            ZynqoException.class, ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.KEY_INVALID))
        .hasMessageNotContaining(key);
  }

  @Test
  void validatorRejectsMissingAndOversizedKeys() {
    ClientKeyValidator validator = new ClientKeyValidator(8);

    assertThatThrownBy(() -> validator.validate(null))
        .isInstanceOfSatisfying(
            ZynqoException.class,
            ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.KEY_MISSING));
    assertThatThrownBy(() -> validator.validate(""))
        .isInstanceOfSatisfying(
            ZynqoException.class,
            ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.KEY_MISSING));
    assertThatThrownBy(() -> validator.validate("123456789"))
        .isInstanceOfSatisfying(
            ZynqoException.class,
            ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.KEY_INVALID));
  }

  @Test
  void validatorRejectsUnsupportedMaxLength() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ClientKeyValidator(0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ClientKeyValidator(ClientKeyValidator.MAX_SUPPORTED_LENGTH + 1));
  }
}
