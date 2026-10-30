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

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

/** Classifies handler method parameters for fingerprinting and validation. */
final class HandlerParameters {

  private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

  private static final List<String> UNSUPPORTED_TYPES =
      List.of("org.springframework.web.multipart.MultipartFile", "jakarta.servlet.http.Part");

  private HandlerParameters() {}

  /** A handler parameter whose value is part of the request fingerprint. */
  record Fingerprinted(int index, String name) {}

  /**
   * Returns the parameter names, or {@code null} if they are not available (the code was compiled
   * without {@code -parameters}).
   */
  static String[] names(Method method) {
    return NAMES.getParameterNames(method);
  }

  static List<Fingerprinted> fingerprinted(Method method) {
    String[] names = names(method);
    List<Fingerprinted> result = new ArrayList<>();
    for (int i = 0; i < method.getParameterCount(); i++) {
      MethodParameter parameter = new MethodParameter(method, i);
      if (isFingerprinted(parameter)) {
        result.add(new Fingerprinted(i, names == null ? "arg" + i : names[i]));
      }
    }
    return result;
  }

  /** Returns a description of why the parameter is unsupported, or {@code null}. */
  static String unsupportedReason(MethodParameter parameter) {
    Class<?> type = parameter.getParameterType();
    if (parameter.hasParameterAnnotation(RequestPart.class)) {
      return "@RequestPart (multipart) arguments cannot be fingerprinted";
    }
    for (String unsupported : UNSUPPORTED_TYPES) {
      if (isAssignable(unsupported, type)) {
        return "multipart arguments of type " + type.getName() + " cannot be fingerprinted";
      }
    }
    if (InputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type)) {
      return "raw request body streams cannot be fingerprinted; use @RequestBody";
    }
    return null;
  }

  private static boolean isFingerprinted(MethodParameter parameter) {
    if (parameter.hasParameterAnnotation(RequestBody.class)
        || parameter.hasParameterAnnotation(PathVariable.class)
        || parameter.hasParameterAnnotation(RequestParam.class)
        || parameter.hasParameterAnnotation(ModelAttribute.class)) {
      return true;
    }
    if (parameter.getParameterAnnotations().length > 0) {
      // Other annotations (@RequestHeader, @CookieValue, @AuthenticationPrincipal, ...) are not
      // part of the payload.
      return false;
    }
    Class<?> type = parameter.getParameterType();
    // Unannotated simple types bind to request parameters; locale and time zone are derived from
    // headers and are not payload.
    return BeanUtils.isSimpleProperty(type)
        && !Locale.class.equals(type)
        && !TimeZone.class.isAssignableFrom(type)
        && !ZoneId.class.isAssignableFrom(type);
  }

  private static boolean isAssignable(String className, Class<?> type) {
    if (!ClassUtils.isPresent(className, type.getClassLoader())) {
      return false;
    }
    return ClassUtils.resolveClassName(className, type.getClassLoader()).isAssignableFrom(type);
  }
}
