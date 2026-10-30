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

import io.github.tanmaysinghx.zynqo.core.identity.RequestFingerprint;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default fingerprinter: SHA-256 over the HTTP method, the operation and the canonical JSON of the
 * payload-carrying handler arguments.
 *
 * <p>Arguments are converted to JSON trees with the application's {@link JsonMapper}, so the
 * fingerprint reflects the values the handler actually received. Properties the mapper ignores (for
 * example {@code @JsonIgnore}) are not part of the fingerprint.
 */
final class JacksonRequestFingerprinter implements ZynqoRequestFingerprinter {

  private final JsonMapper mapper;
  private final Map<Method, List<HandlerParameters.Fingerprinted>> parameters =
      new ConcurrentHashMap<>();

  JacksonRequestFingerprinter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public RequestFingerprint fingerprint(ZynqoInvocationContext invocation) {
    RequestFingerprint.Builder builder =
        RequestFingerprint.builder()
            .add("http-method", invocation.request().getMethod())
            .add("operation", invocation.operation());
    for (HandlerParameters.Fingerprinted parameter :
        parameters.computeIfAbsent(invocation.method(), HandlerParameters::fingerprinted)) {
      Object value = invocation.arguments()[parameter.index()];
      builder.add("arg:" + parameter.index() + ":" + parameter.name(), canonical(value, parameter));
    }
    return builder.build();
  }

  private String canonical(Object value, HandlerParameters.Fingerprinted parameter) {
    try {
      JsonNode tree = mapper.valueToTree(value);
      return tree == null ? "null" : CanonicalJson.write(tree);
    } catch (JacksonException | IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Cannot fingerprint argument '"
              + parameter.name()
              + "' of type "
              + (value == null ? "null" : value.getClass().getName())
              + "; it must be serializable to JSON",
          ex);
    }
  }
}
