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
package io.github.tanmaysinghx.zynqo.it;

import io.github.tanmaysinghx.zynqo.annotation.Zynqo;
import io.github.tanmaysinghx.zynqo.core.ZynqoContext;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.net.URI;
import java.security.Principal;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Application started several times in one JVM to model independent instances sharing one Redis.
 * Executions are recorded in a JVM-wide log so tests can count them across instances.
 */
@SpringBootApplication
public class ClusterApplication {

  /** Every execution of a protected handler, across all instances. */
  public static final Queue<Execution> EXECUTIONS = new ConcurrentLinkedQueue<>();

  /** When set, the {@code /payments} handler blocks until this latch opens. */
  public static final AtomicReference<CountDownLatch> HOLD = new AtomicReference<>();

  public record Execution(String instance, String reference, long fence) {}

  public record PaymentRequest(String reference, long amountCents) {}

  public record Payment(String reference, long amountCents, String instance, long fence) {}

  /** Test-only principal: taken from a header. Real applications use Spring Security. */
  @Bean
  FilterRegistrationBean<Filter> testPrincipal() {
    Filter filter =
        (request, response, chain) -> {
          HttpServletRequest http = (HttpServletRequest) request;
          String user = http.getHeader("X-Test-User");
          chain.doFilter(
              user == null
                  ? request
                  : new HttpServletRequestWrapper(http) {
                    @Override
                    public Principal getUserPrincipal() {
                      return () -> user;
                    }
                  },
              response);
        };
    return new FilterRegistrationBean<>(filter);
  }

  @Bean
  Gate gate() {
    return new Gate();
  }

  /** Holds the slow handler of one instance inside its execution. */
  public static final class Gate {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    public boolean awaitEntered() throws InterruptedException {
      return entered.await(30, TimeUnit.SECONDS);
    }

    public void open() {
      release.countDown();
    }

    void pass() throws InterruptedException {
      entered.countDown();
      if (!release.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("gate was not opened");
      }
    }
  }

  @RestController
  static class PaymentController {

    private final String instance;
    private final Gate gate;

    PaymentController(@Value("${test.instance}") String instance, Gate gate) {
      this.instance = instance;
      this.gate = gate;
    }

    private Payment record(PaymentRequest request) {
      long fence = ZynqoContext.currentFencingToken().orElseThrow();
      EXECUTIONS.add(new Execution(instance, request.reference(), fence));
      return new Payment(request.reference(), request.amountCents(), instance, fence);
    }

    @Zynqo
    @PostMapping("/payments")
    public ResponseEntity<Payment> pay(@RequestBody PaymentRequest request)
        throws InterruptedException {
      CountDownLatch hold = HOLD.get();
      if (hold != null && !hold.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("hold was not released");
      }
      Payment payment = record(request);
      return ResponseEntity.created(URI.create("/payments/" + payment.reference())).body(payment);
    }

    @Zynqo(lease = "1s")
    @PostMapping("/payments/slow")
    public Payment slow(@RequestBody PaymentRequest request) throws InterruptedException {
      gate.pass();
      return record(request);
    }
  }
}
