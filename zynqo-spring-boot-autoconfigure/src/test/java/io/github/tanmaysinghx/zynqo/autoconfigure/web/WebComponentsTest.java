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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoProperties;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class WebComponentsTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  record Item(String name, BigDecimal price) {}

  record Order(String reference, List<Item> items, Map<String, Object> attributes) {}

  @RestController
  static class Handlers {
    @Zynqo
    public ResponseEntity<Order> entity(@RequestBody Order order) {
      return null;
    }

    @Zynqo(key = "#order.reference")
    public Order value(@RequestBody Order order, @RequestHeader("X-Trace") String trace) {
      return null;
    }

    @Zynqo(key = "#order.reference()")
    public Order accessor(@RequestBody Order order) {
      return null;
    }

    @Zynqo(key = "#p1")
    public List<Order> list(@PathVariable String id, @RequestParam UUID token, Locale locale) {
      return null;
    }

    @Zynqo
    public void nothing() {}

    @Zynqo
    public ResponseEntity<Void> noBody() {
      return null;
    }

    @Zynqo
    public ResponseEntity<?> wildcard() {
      return null;
    }

    @Zynqo
    public CompletableFuture<Order> async() {
      return null;
    }

    @Zynqo
    public SseEmitter stream() {
      return null;
    }

    @Zynqo
    public Resource resource() {
      return null;
    }

    @Zynqo
    public Optional<Order> optional() {
      return null;
    }

    @Zynqo
    public HttpEntity<Order> httpEntity() {
      return null;
    }

    @Zynqo
    public ResponseEntity<InputStream> streamEntity() {
      return null;
    }

    @Zynqo
    public Order upload(@RequestPart("f") MultipartFile file) {
      return null;
    }

    @Zynqo
    public Order raw(InputStream body) {
      return null;
    }

    @Zynqo(key = "#order.(")
    public Order badExpression(@RequestBody Order order) {
      return null;
    }

    @Zynqo(lease = "soon", retention = "-5m")
    public Order badDurations() {
      return null;
    }

    @Zynqo
    public final Order finalMethod() {
      return null;
    }

    @Zynqo(operation = "custom", lease = "PT2M", retention = "15m")
    public Order custom() {
      return null;
    }

    @Zynqo(key = "#order")
    public Order objectKey(@RequestBody Order order) {
      return null;
    }
  }

  @Controller
  static class ViewController {
    @Zynqo
    public String view() {
      return "view-name";
    }
  }

  private static Method method(Class<?> type, String name) {
    for (Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return method;
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static OperationMetadata metadata(String name) {
    return OperationMetadata.create(method(Handlers.class, name), new ZynqoProperties(), MAPPER);
  }

  private static final Order ORDER =
      new Order(
          "ref-1",
          List.of(new Item("book", new BigDecimal("12.50"))),
          Map.of("gift", true, "note", "a\"b\n"));

  // ---- canonical JSON -------------------------------------------------------------------

  @Test
  void canonicalJsonSortsKeysNormalisesNumbersAndEscapes() {
    var node =
        MAPPER.readTree(
            "{\"b\":1.50,\"a\":{\"z\":[3,1.0,0.00],\"y\":null},\"c\":\"q\\\"\\n\\u0001é\"}");

    assertThat(CanonicalJson.write(node))
        .isEqualTo("{\"a\":{\"y\":null,\"z\":[3,1,0]},\"b\":1.5,\"c\":\"q\\\"\\n\\u0001é\"}");
  }

  @Test
  void canonicalJsonHandlesBinaryBooleansAndLargeNumbers() {
    var node =
        MAPPER.valueToTree(
            Map.of(
                "bytes",
                new byte[] {1, 2, 3},
                "flag",
                false,
                "big",
                new BigDecimal("1E+3"),
                "huge",
                new java.math.BigInteger("123456789012345678901234567890")));

    assertThat(CanonicalJson.write(node))
        .isEqualTo(
            "{\"big\":1000,\"bytes\":\"AQID\",\"flag\":false,\"huge\":123456789012345678901234567890}");
  }

  @Test
  void canonicalJsonEscapesAllControlCharacters() {
    assertThat(CanonicalJson.write(MAPPER.valueToTree("\b\f\r\t\\")))
        .isEqualTo("\"\\b\\f\\r\\t\\\\\"");
  }

  // ---- fingerprinting -------------------------------------------------------------------

  private static RequestFingerprint fingerprint(String name, Object... args) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
    return new JacksonRequestFingerprinter(MAPPER)
        .fingerprint(
            new ZynqoInvocationContext(
                request, method(Handlers.class, name), args, "POST /orders"));
  }

  @Test
  void fingerprintIgnoresHeadersAndLocaleButCoversPayloadAndParameters() {
    assertThat(fingerprint("value", ORDER, "trace-1"))
        .isEqualTo(fingerprint("value", ORDER, "trace-2"));
    assertThat(fingerprint("value", ORDER, "t"))
        .isNotEqualTo(fingerprint("value", new Order("ref-1", List.of(), Map.of()), "t"));

    UUID token = UUID.randomUUID();
    assertThat(fingerprint("list", "1", token, Locale.ENGLISH))
        .isEqualTo(fingerprint("list", "1", token, Locale.GERMAN));
    assertThat(fingerprint("list", "1", token, Locale.ENGLISH))
        .isNotEqualTo(fingerprint("list", "2", token, Locale.ENGLISH));
  }

  @Test
  void fingerprintIsIndependentOfNumericScaleAndMapOrder() {
    Order a =
        new Order("r", List.of(new Item("x", new BigDecimal("1.0"))), Map.of("k1", 1, "k2", 2));
    Order b =
        new Order("r", List.of(new Item("x", new BigDecimal("1.00"))), Map.of("k2", 2, "k1", 1));

    assertThat(fingerprint("entity", a)).isEqualTo(fingerprint("entity", b));
  }

  @Test
  void fingerprintCoversHttpMethodAndOperation() {
    Method m = method(Handlers.class, "entity");
    JacksonRequestFingerprinter fp = new JacksonRequestFingerprinter(MAPPER);
    var post =
        new ZynqoInvocationContext(
            new MockHttpServletRequest("POST", "/"), m, new Object[] {ORDER}, "op");
    var put =
        new ZynqoInvocationContext(
            new MockHttpServletRequest("PUT", "/"), m, new Object[] {ORDER}, "op");
    var other =
        new ZynqoInvocationContext(
            new MockHttpServletRequest("POST", "/"), m, new Object[] {ORDER}, "op2");

    assertThat(fp.fingerprint(post))
        .isNotEqualTo(fp.fingerprint(put))
        .isNotEqualTo(fp.fingerprint(other));
  }

  @Test
  void unserializableArgumentFailsWithDescriptiveError() {
    Object unserializable =
        new Object() {
          @SuppressWarnings("unused")
          public Object getSelf() {
            return this;
          }
        };

    assertThatThrownBy(() -> fingerprint("entity", unserializable))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot fingerprint argument 'order'");
  }

  // ---- response codec ------------------------------------------------------------------

  @Test
  void responseEntityRoundTripKeepsStatusAllowListedHeadersAndTypedBody() {
    OperationMetadata metadata = metadata("entity");
    ResponseEntity<Order> entity =
        ResponseEntity.status(HttpStatus.CREATED)
            .header("Location", "/orders/1")
            .header("location-extra", "no")
            .header("Set-Cookie", "s=1")
            .header("Content-Type", "application/json")
            .body(ORDER);

    StoredResponse stored = metadata.codec().encode(entity);
    ResponseEntity<?> replayed = (ResponseEntity<?>) metadata.codec().decode(stored);

    assertThat(stored.headers()).containsOnlyKeys("Location", "Content-Type");
    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed.getHeaders().getFirst("Location")).isEqualTo("/orders/1");
    assertThat(replayed.getHeaders().getFirst("Set-Cookie")).isNull();
    assertThat(replayed.getBody()).isEqualTo(ORDER);
  }

  @Test
  void valueAndGenericCollectionsRoundTripIntoDeclaredTypes() {
    StoredResponse stored = metadata("value").codec().encode(ORDER);
    assertThat(metadata("value").codec().decode(stored)).isEqualTo(ORDER);

    OperationMetadata list = metadata("list");
    Object replayed = list.codec().decode(list.codec().encode(List.of(ORDER)));
    assertThat(replayed)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .singleElement()
        .isEqualTo(ORDER);
  }

  @Test
  void voidNullAndEmptyResponsesRoundTrip() {
    assertThat(metadata("nothing").codec().decode(metadata("nothing").codec().encode(null)))
        .isNull();
    assertThat(metadata("value").codec().decode(metadata("value").codec().encode(null))).isNull();
    assertThat(metadata("entity").codec().decode(metadata("entity").codec().encode(null))).isNull();

    OperationMetadata noBody = metadata("noBody");
    ResponseEntity<?> replayed =
        (ResponseEntity<?>)
            noBody.codec().decode(noBody.codec().encode(ResponseEntity.noContent().build()));
    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(replayed.getBody()).isNull();
  }

  @Test
  void wildcardEntityBodyReplaysAsPlainJsonStructure() {
    OperationMetadata wildcard = metadata("wildcard");
    ResponseEntity<?> replayed =
        (ResponseEntity<?>)
            wildcard.codec().decode(wildcard.codec().encode(ResponseEntity.ok(Map.of("a", 1))));
    assertThat(replayed.getBody()).isEqualTo(Map.of("a", 1));
  }

  @Test
  void streamingBodyInsideWildcardEntityIsNotStorable() {
    OperationMetadata wildcard = metadata("wildcard");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> wildcard.codec().encode(ResponseEntity.ok(InputStream.nullInputStream())));
  }

  // ---- metadata validation ---------------------------------------------------------------

  @Test
  void rejectsUnsupportedReturnTypesAndParameters() {
    for (String name :
        List.of(
            "async",
            "stream",
            "resource",
            "optional",
            "httpEntity",
            "streamEntity",
            "upload",
            "raw")) {
      assertThatIllegalArgumentException().as(name).isThrownBy(() -> metadata(name));
    }
  }

  @Test
  void rejectsInvalidExpressionDurationsAndFinalMethods() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metadata("badExpression"))
        .withMessageContaining("key expression");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metadata("badDurations"))
        .withMessageContaining("lease 'soon'")
        .withMessageContaining("retention must be positive");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metadata("finalMethod"))
        .withMessageContaining("final");
  }

  @Test
  void rejectsViewHandlers() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OperationMetadata.create(
                    method(ViewController.class, "view"), new ZynqoProperties(), MAPPER))
        .withMessageContaining("@ResponseBody");
  }

  @Test
  void rejectsMethodsWithoutAnnotation() throws NoSuchMethodException {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OperationMetadata.create(
                    Object.class.getMethod("toString"), new ZynqoProperties(), MAPPER));
  }

  @Test
  void annotationOverridesDurationsAndOperation() {
    OperationMetadata metadata = metadata("custom");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");

    assertThat(metadata.policy().lease()).isEqualTo(Duration.ofMinutes(2));
    assertThat(metadata.policy().completedRetention()).isEqualTo(Duration.ofMinutes(15));
    assertThat(metadata.policy().failedRetention()).isEqualTo(Duration.ofHours(24));
    assertThat(metadata.operation(request)).isEqualTo("custom");
  }

  @Test
  void defaultOperationUsesMatchedRoutePattern() {
    OperationMetadata metadata = metadata("entity");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders/1");
    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

    assertThat(metadata.operation(request)).isEqualTo("POST /orders/{id}");
    assertThat(metadata.operation(new MockHttpServletRequest("PUT", "/")))
        .isEqualTo("PUT " + Handlers.class.getName() + "#entity");
  }

  @Test
  void resolvesKeysFromSpelPropertyAccessorAndIndex() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    UUID token = UUID.randomUUID();

    assertThat(metadata("value").clientKey(new Object[] {ORDER, "t"}, request)).isEqualTo("ref-1");
    assertThat(metadata("accessor").clientKey(new Object[] {ORDER}, request)).isEqualTo("ref-1");
    assertThat(metadata("list").clientKey(new Object[] {"1", token, Locale.ROOT}, request))
        .isEqualTo(token.toString());
    assertThat(
            metadata("value")
                .clientKey(new Object[] {new Order(null, List.of(), Map.of()), "t"}, request))
        .isNull();
  }

  @Test
  void spelKeyOfUnsupportedTypeIsAProgrammingError() {
    assertThatThrownBy(
            () ->
                metadata("objectKey").clientKey(new Object[] {ORDER}, new MockHttpServletRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must evaluate to a String");
  }

  @Test
  void spelContextForbidsTypeReferences() {
    record Probe(String value) {}
    // Type references are not available in a read-only data binding context.
    var context =
        org.springframework.expression.spel.support.SimpleEvaluationContext.forReadOnlyDataBinding()
            .build();
    assertThatThrownBy(
            () ->
                new org.springframework.expression.spel.standard.SpelExpressionParser()
                    .parseExpression("T(java.lang.Runtime).getRuntime()")
                    .getValue(context, new Probe("x")))
        .isInstanceOf(org.springframework.expression.EvaluationException.class);
  }

  @Test
  void headerKeyMustAppearAtMostOnce() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertThat(metadata("entity").clientKey(new Object[] {ORDER}, request)).isNull();
    request.addHeader("Idempotency-Key", "a");
    assertThat(metadata("entity").clientKey(new Object[] {ORDER}, request)).isEqualTo("a");
    request.addHeader("Idempotency-Key", "b");
    assertThatThrownBy(() -> metadata("entity").clientKey(new Object[] {ORDER}, request))
        .isInstanceOfSatisfying(
            ZynqoException.class,
            ex -> assertThat(ex.code()).isEqualTo(ZynqoErrorCode.KEY_INVALID));
  }

  // ---- scope resolution ------------------------------------------------------------------

  @Test
  void defaultScopeResolverUsesUserPrincipalAndOptionalTenantHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertThat(new DefaultZynqoScopeResolver("").resolve(request))
        .isEqualTo(new ZynqoCaller("", ""));
    assertThat(new DefaultZynqoScopeResolver(null).resolve(request).anonymous()).isTrue();

    request.setUserPrincipal(() -> "alice");
    request.addHeader("X-Tenant", "acme");
    assertThat(new DefaultZynqoScopeResolver("X-Tenant").resolve(request))
        .isEqualTo(new ZynqoCaller("acme", "alice"));
    assertThat(new DefaultZynqoScopeResolver("X-Other").resolve(request))
        .isEqualTo(new ZynqoCaller("", "alice"));
    assertThat(new ZynqoCaller("acme", "alice").toString())
        .doesNotContain("acme")
        .doesNotContain("alice");
  }

  // ---- problem details -------------------------------------------------------------------

  @Test
  void everyErrorCodeMapsToStableStatusTypeAndTitle() {
    ZynqoProblemDetailsAdvice advice = new ZynqoProblemDetailsAdvice();
    for (ZynqoErrorCode code : ZynqoErrorCode.values()) {
      ResponseEntity<ProblemDetail> response = advice.handle(new ZynqoException(code, "detail"));
      ProblemDetail problem = response.getBody();
      assertThat(problem).isNotNull();
      assertThat(problem.getType().toString()).startsWith("urn:zynqo:problem:").doesNotContain("_");
      assertThat(problem.getTitle()).isNotBlank();
      assertThat(problem.getProperties()).containsEntry("code", code.name());
      assertThat(response.getStatusCode().value()).isEqualTo(problem.getStatus());
    }
    assertThat(ZynqoProblemDetailsAdvice.status(ZynqoErrorCode.FINGERPRINT_MISMATCH).value())
        .isEqualTo(422);
    assertThat(ZynqoProblemDetailsAdvice.status(ZynqoErrorCode.REQUEST_IN_PROGRESS).value())
        .isEqualTo(409);
    assertThat(ZynqoProblemDetailsAdvice.status(ZynqoErrorCode.STORAGE_UNAVAILABLE).value())
        .isEqualTo(503);
  }

  @Test
  void retryAfterHeaderIsWholeSeconds() {
    ResponseEntity<ProblemDetail> response =
        new ZynqoProblemDetailsAdvice()
            .handle(
                new ZynqoException(
                    ZynqoErrorCode.REQUEST_IN_PROGRESS, "busy", Duration.ofSeconds(7), null));

    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
  }
}
