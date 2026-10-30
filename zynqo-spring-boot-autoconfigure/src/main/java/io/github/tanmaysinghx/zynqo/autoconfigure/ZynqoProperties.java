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
package io.github.tanmaysinghx.zynqo.autoconfigure;

import io.github.tanmaysinghx.zynqo.core.ExceptionDisposition;
import io.github.tanmaysinghx.zynqo.core.identity.ClientKeyValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for Zynqo.
 *
 * <p>All durations are independent: the claim lease, the completed and failed record retention, the
 * duplicate wait timeout and the store's own command timeout (configured on the Redis client, for
 * example {@code spring.data.redis.timeout}) control different things.
 */
@ConfigurationProperties(prefix = "zynqo")
public class ZynqoProperties {

  /**
   * Headers that are never stored or replayed, regardless of configuration: credentials, cookies,
   * hop-by-hop and transport metadata.
   */
  public static final Set<String> FORBIDDEN_REPLAY_HEADERS =
      Set.of(
          "authorization",
          "connection",
          "content-length",
          "cookie",
          "date",
          "idempotent-replayed",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "proxy-connection",
          "set-cookie",
          "set-cookie2",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "www-authenticate");

  /** Whether Zynqo is enabled. When disabled no Zynqo beans are created. */
  private boolean enabled = true;

  /**
   * Default advisor order: after every Spring Security method interceptor (orders 100 to 600, so
   * authorization is re-evaluated for replays) and before transaction and cache advice ({@code
   * Ordered.LOWEST_PRECEDENCE}, so completion is recorded only after commit).
   */
  public static final int DEFAULT_INTERCEPTOR_ORDER = 1000;

  /**
   * Order of the @Zynqo method interceptor among other advisors. The default runs inside Spring
   * Security method interceptors and outside @Transactional.
   */
  private int interceptorOrder = DEFAULT_INTERCEPTOR_ORDER;

  private final Storage storage = new Storage();
  private final Key key = new Key();
  private final Scope scope = new Scope();
  private final Execution execution = new Execution();
  private final Retention retention = new Retention();
  private final Response response = new Response();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getInterceptorOrder() {
    return interceptorOrder;
  }

  public void setInterceptorOrder(int interceptorOrder) {
    this.interceptorOrder = interceptorOrder;
  }

  public Storage getStorage() {
    return storage;
  }

  public Key getKey() {
    return key;
  }

  public Scope getScope() {
    return scope;
  }

  public Execution getExecution() {
    return execution;
  }

  public Retention getRetention() {
    return retention;
  }

  public Response getResponse() {
    return response;
  }

  /**
   * Validates the configuration.
   *
   * @throws ZynqoConfigurationException describing every invalid property
   */
  public void validate() {
    List<String> problems = new ArrayList<>();
    if (storage.keyPrefix == null || storage.keyPrefix.isBlank()) {
      problems.add("zynqo.storage.key-prefix must not be blank");
    }
    if (key.headerName == null || key.headerName.isBlank()) {
      problems.add("zynqo.key.header-name must not be blank");
    }
    if (key.maxLength < 1 || key.maxLength > ClientKeyValidator.MAX_SUPPORTED_LENGTH) {
      problems.add(
          "zynqo.key.max-length must be between 1 and " + ClientKeyValidator.MAX_SUPPORTED_LENGTH);
    }
    requirePositive(problems, "zynqo.execution.lease-duration", execution.leaseDuration);
    requirePositive(problems, "zynqo.execution.poll-interval", execution.pollInterval);
    if (execution.waitTimeout == null || execution.waitTimeout.isNegative()) {
      problems.add("zynqo.execution.wait-timeout must be zero or positive");
    }
    if (execution.onException == null) {
      problems.add("zynqo.execution.on-exception must be set");
    }
    requirePositive(problems, "zynqo.retention.completed-ttl", retention.completedTtl);
    requirePositive(problems, "zynqo.retention.failed-ttl", retention.failedTtl);
    if (response.maxBodySize == null
        || response.maxBodySize.toBytes() < 1
        || response.maxBodySize.toBytes() > Integer.MAX_VALUE) {
      problems.add("zynqo.response.max-body-size must be between 1B and 2GB");
    }
    for (String header : response.replayHeaders) {
      if (FORBIDDEN_REPLAY_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
        problems.add(
            "zynqo.response.replay-headers must not contain '" + header + "' (never replayed)");
      }
    }
    if (!problems.isEmpty()) {
      throw new ZynqoConfigurationException(
          "Invalid Zynqo configuration:\n  - " + String.join("\n  - ", problems),
          "Correct the listed zynqo.* properties.");
    }
  }

  private static void requirePositive(List<String> problems, String name, Duration value) {
    if (value == null || value.isNegative() || value.isZero()) {
      problems.add(name + " must be positive");
    }
  }

  /** Storage settings. */
  public static class Storage {

    /** Prefix of every Redis key written by Zynqo. Use a distinct prefix per application. */
    private String keyPrefix = "zynqo:";

    public String getKeyPrefix() {
      return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
      this.keyPrefix = keyPrefix;
    }
  }

  /** Client idempotency key settings. */
  public static class Key {

    /** Request header that carries the idempotency key when @Zynqo declares no key expression. */
    private String headerName = "Idempotency-Key";

    /** Maximum accepted key length in characters. */
    private int maxLength = 255;

    public String getHeaderName() {
      return headerName;
    }

    public void setHeaderName(String headerName) {
      this.headerName = headerName;
    }

    public int getMaxLength() {
      return maxLength;
    }

    public void setMaxLength(int maxLength) {
      this.maxLength = maxLength;
    }
  }

  /** Key scoping settings. */
  public static class Scope {

    /**
     * Whether an authenticated principal is required. When true, anonymous requests to @Zynqo
     * endpoints are rejected with 403. Disabling this makes all anonymous callers share one key
     * space.
     */
    private boolean requirePrincipal = true;

    /**
     * Request header that identifies the tenant, or empty for single-tenant applications. Use only
     * a header that is set or validated by trusted infrastructure.
     */
    private String tenantHeader = "";

    public boolean isRequirePrincipal() {
      return requirePrincipal;
    }

    public void setRequirePrincipal(boolean requirePrincipal) {
      this.requirePrincipal = requirePrincipal;
    }

    public String getTenantHeader() {
      return tenantHeader;
    }

    public void setTenantHeader(String tenantHeader) {
      this.tenantHeader = tenantHeader;
    }
  }

  /** Execution settings. */
  public static class Execution {

    /**
     * How long a claim stays exclusive. Must exceed the worst-case execution time of protected
     * operations; afterwards another request may take over execution.
     */
    private Duration leaseDuration = Duration.ofSeconds(60);

    /**
     * How long a duplicate request waits for an in-progress execution before receiving 409. Zero
     * rejects immediately.
     */
    private Duration waitTimeout = Duration.ZERO;

    /** Delay between checks while a duplicate request waits. */
    private Duration pollInterval = Duration.ofMillis(50);

    /**
     * What happens to the record when the protected operation throws: RELEASE allows a retry to
     * execute again; MARK_FAILED blocks re-execution until the failed record expires.
     */
    private ExceptionDisposition onException = ExceptionDisposition.RELEASE;

    public Duration getLeaseDuration() {
      return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration = leaseDuration;
    }

    public Duration getWaitTimeout() {
      return waitTimeout;
    }

    public void setWaitTimeout(Duration waitTimeout) {
      this.waitTimeout = waitTimeout;
    }

    public Duration getPollInterval() {
      return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
      this.pollInterval = pollInterval;
    }

    public ExceptionDisposition getOnException() {
      return onException;
    }

    public void setOnException(ExceptionDisposition onException) {
      this.onException = onException;
    }
  }

  /** Record retention settings. */
  public static class Retention {

    /** How long completed responses are retained and replayed. */
    private Duration completedTtl = Duration.ofHours(24);

    /** How long failed records block re-execution of their key. */
    private Duration failedTtl = Duration.ofHours(24);

    public Duration getCompletedTtl() {
      return completedTtl;
    }

    public void setCompletedTtl(Duration completedTtl) {
      this.completedTtl = completedTtl;
    }

    public Duration getFailedTtl() {
      return failedTtl;
    }

    public void setFailedTtl(Duration failedTtl) {
      this.failedTtl = failedTtl;
    }
  }

  /** Stored response settings. */
  public static class Response {

    /**
     * Maximum size of a stored response body. Larger responses are returned to the caller but not
     * stored; their key is marked failed.
     */
    private DataSize maxBodySize = DataSize.ofKilobytes(64);

    /**
     * Response headers from ResponseEntity results that are stored and replayed. Credentials,
     * cookies and hop-by-hop headers are never replayed.
     */
    private List<String> replayHeaders =
        new ArrayList<>(
            List.of("Location", "Content-Type", "Content-Language", "ETag", "Last-Modified"));

    public DataSize getMaxBodySize() {
      return maxBodySize;
    }

    public void setMaxBodySize(DataSize maxBodySize) {
      this.maxBodySize = maxBodySize;
    }

    public List<String> getReplayHeaders() {
      return replayHeaders;
    }

    public void setReplayHeaders(List<String> replayHeaders) {
      this.replayHeaders = replayHeaders;
    }
  }
}
