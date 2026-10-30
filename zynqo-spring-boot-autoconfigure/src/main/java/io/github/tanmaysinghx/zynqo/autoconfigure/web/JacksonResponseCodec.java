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

import io.github.tanmaysinghx.zynqo.autoconfigure.ZynqoProperties;
import io.github.tanmaysinghx.zynqo.core.ResponseCodec;
import io.github.tanmaysinghx.zynqo.core.store.StoredResponse;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores handler results as JSON bound to the handler's declared return type.
 *
 * <p>Supported return types:
 *
 * <ul>
 *   <li>{@code void} / {@code Void}: nothing is stored besides completion;
 *   <li>{@code ResponseEntity<T>}: status code, allow-listed headers and the JSON body;
 *   <li>any other JSON-serializable type {@code T} written with {@code @ResponseBody}.
 * </ul>
 *
 * <p>On replay the JSON is deserialized into the declared type (never into a type named by the
 * stored data) and returned to Spring MVC, which renders it through the normal message converters.
 * Asynchronous, streaming, resource and view return types are rejected at startup.
 */
final class JacksonResponseCodec implements ResponseCodec<Object> {

  private static final List<String> UNSUPPORTED_TYPE_NAMES =
      List.of(
          "org.reactivestreams.Publisher",
          "kotlinx.coroutines.flow.Flow",
          "org.springframework.web.context.request.async.DeferredResult",
          "org.springframework.web.context.request.async.WebAsyncTask",
          "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter",
          "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody");

  private static final List<Class<?>> UNSUPPORTED_TYPES =
      List.of(
          CompletionStage.class,
          Future.class,
          Callable.class,
          Resource.class,
          InputStream.class,
          ModelAndView.class,
          View.class,
          Optional.class);

  private enum Kind {
    VOID,
    RESPONSE_ENTITY,
    VALUE
  }

  private final JsonMapper mapper;
  private final Kind kind;
  private final JavaType bodyType;
  private final Set<String> replayHeaders;

  private JacksonResponseCodec(
      JsonMapper mapper, Kind kind, JavaType bodyType, Set<String> replayHeaders) {
    this.mapper = mapper;
    this.kind = kind;
    this.bodyType = bodyType;
    this.replayHeaders = replayHeaders;
  }

  /**
   * Creates a codec for a handler method.
   *
   * @throws IllegalArgumentException if the return type is not supported
   */
  static JacksonResponseCodec forMethod(
      Method method, JsonMapper mapper, Collection<String> replayHeaders) {
    ResolvableType returnType = ResolvableType.forMethodReturnType(method);
    Class<?> raw = returnType.toClass();
    Set<String> headers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    headers.addAll(replayHeaders);
    if (raw == void.class || raw == Void.class) {
      return new JacksonResponseCodec(mapper, Kind.VOID, null, headers);
    }
    if (ResponseEntity.class.isAssignableFrom(raw)) {
      ResolvableType body = returnType.as(ResponseEntity.class).getGeneric(0);
      checkSupported(body.resolve(Object.class), "ResponseEntity body");
      Kind kind = Kind.RESPONSE_ENTITY;
      Class<?> bodyClass = body.resolve(Object.class);
      if (bodyClass == Void.class) {
        return new JacksonResponseCodec(mapper, kind, null, headers);
      }
      return new JacksonResponseCodec(mapper, kind, javaType(mapper, body), headers);
    }
    if (HttpEntity.class.isAssignableFrom(raw)) {
      throw new IllegalArgumentException(
          "return type " + raw.getName() + " is not supported; return ResponseEntity instead");
    }
    checkSupported(raw, "return type");
    return new JacksonResponseCodec(mapper, Kind.VALUE, javaType(mapper, returnType), headers);
  }

  private static JavaType javaType(JsonMapper mapper, ResolvableType type) {
    Type resolved = type.getType();
    return mapper.getTypeFactory().constructType(resolved);
  }

  private static void checkSupported(Class<?> type, String what) {
    for (Class<?> unsupported : UNSUPPORTED_TYPES) {
      if (unsupported.isAssignableFrom(type)) {
        throw new IllegalArgumentException(
            what + " " + type.getName() + " is not supported (asynchronous, streaming or view)");
      }
    }
    for (String name : UNSUPPORTED_TYPE_NAMES) {
      if (ClassUtils.isPresent(name, type.getClassLoader())
          && ClassUtils.resolveClassName(name, type.getClassLoader()).isAssignableFrom(type)) {
        throw new IllegalArgumentException(
            what + " " + type.getName() + " is not supported (asynchronous or streaming)");
      }
    }
  }

  @Override
  public StoredResponse encode(Object result) {
    return switch (kind) {
      case VOID -> new StoredResponse(0, Map.of(), null);
      case VALUE -> new StoredResponse(0, Map.of(), result == null ? null : json(result));
      case RESPONSE_ENTITY -> {
        if (result == null) {
          yield new StoredResponse(0, Map.of(), null);
        }
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        Object body = entity.getBody();
        if (body != null) {
          checkSupported(body.getClass(), "ResponseEntity body");
        }
        yield new StoredResponse(
            entity.getStatusCode().value(),
            replayable(entity.getHeaders()),
            body == null ? null : json(body));
      }
    };
  }

  @Override
  public Object decode(StoredResponse response) {
    return switch (kind) {
      case VOID -> null;
      case VALUE -> response.body() == null ? null : mapper.readValue(response.body(), bodyType);
      case RESPONSE_ENTITY -> {
        if (response.status() == 0) {
          yield null;
        }
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach(headers::addAll);
        byte[] body = response.body();
        Object value = body == null || bodyType == null ? null : mapper.readValue(body, bodyType);
        yield ResponseEntity.status(response.status()).headers(headers).body(value);
      }
    };
  }

  private byte[] json(Object value) {
    return mapper.writeValueAsBytes(value);
  }

  private Map<String, List<String>> replayable(HttpHeaders headers) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> header : headers.headerSet()) {
      String name = header.getKey();
      if (replayHeaders.contains(name)
          && !ZynqoProperties.FORBIDDEN_REPLAY_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        result.put(name, new ArrayList<>(header.getValue()));
      }
    }
    return result;
  }
}
