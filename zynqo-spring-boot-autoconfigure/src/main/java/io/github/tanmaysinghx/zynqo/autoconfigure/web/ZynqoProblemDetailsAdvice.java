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

import io.github.tanmaysinghx.zynqo.core.error.ZynqoErrorCode;
import io.github.tanmaysinghx.zynqo.core.error.ZynqoException;
import java.net.URI;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link ZynqoException}s as RFC 9457 Problem Details.
 *
 * <p>Every response has a stable {@code type} URI ({@code urn:zynqo:problem:<code>}) and a {@code
 * code} member holding the {@link ZynqoErrorCode} name. Retryable errors carry a {@code
 * Retry-After} header. Applications can replace this advice by declaring a bean of this type.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ZynqoProblemDetailsAdvice {

  /**
   * Maps an error code to its HTTP status.
   *
   * @param code the error code
   * @return the status
   */
  public static HttpStatus status(ZynqoErrorCode code) {
    return switch (code) {
      case KEY_MISSING, KEY_INVALID -> HttpStatus.BAD_REQUEST;
      case PRINCIPAL_REQUIRED -> HttpStatus.FORBIDDEN;
      case FINGERPRINT_MISMATCH -> HttpStatus.UNPROCESSABLE_CONTENT;
      case REQUEST_IN_PROGRESS, PREVIOUS_ATTEMPT_FAILED -> HttpStatus.CONFLICT;
      case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case REPLAY_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  /**
   * Returns the problem type URI of an error code.
   *
   * @param code the error code
   * @return the type URI
   */
  public static URI type(ZynqoErrorCode code) {
    return URI.create(
        "urn:zynqo:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }

  /**
   * Handles a Zynqo error.
   *
   * @param ex the error
   * @return the Problem Details response
   */
  @ExceptionHandler(ZynqoException.class)
  public ResponseEntity<ProblemDetail> handle(ZynqoException ex) {
    HttpStatus status = status(ex.code());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    problem.setType(type(ex.code()));
    problem.setTitle(title(ex.code()));
    problem.setProperty("code", ex.code().name());
    HttpHeaders headers = new HttpHeaders();
    ex.retryAfter()
        .ifPresent(delay -> headers.set(HttpHeaders.RETRY_AFTER, Long.toString(delay.toSeconds())));
    return ResponseEntity.status(status).headers(headers).body(problem);
  }

  private static String title(ZynqoErrorCode code) {
    return switch (code) {
      case KEY_MISSING -> "Idempotency key missing";
      case KEY_INVALID -> "Idempotency key invalid";
      case PRINCIPAL_REQUIRED -> "Authentication required for idempotent request";
      case FINGERPRINT_MISMATCH -> "Idempotency key reused with different request";
      case REQUEST_IN_PROGRESS -> "Request in progress";
      case PREVIOUS_ATTEMPT_FAILED -> "Previous attempt failed";
      case STORAGE_UNAVAILABLE -> "Idempotency store unavailable";
      case REPLAY_FAILED -> "Stored response cannot be replayed";
    };
  }
}
