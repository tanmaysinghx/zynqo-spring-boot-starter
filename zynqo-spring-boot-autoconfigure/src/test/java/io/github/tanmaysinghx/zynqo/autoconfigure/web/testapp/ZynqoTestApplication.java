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
package io.github.tanmaysinghx.zynqo.autoconfigure.web.testapp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Minimal Spring MVC application used by the HTTP contract tests. */
@SpringBootApplication
public class ZynqoTestApplication {

  /** Test-only credentials; never use static credentials outside tests. */
  public static final String PASSWORD = "test-only-password";

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth -> auth.requestMatchers("/public/**").permitAll().anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean
  UserDetailsService users() {
    return new InMemoryUserDetailsManager(
        User.withUsername("alice").password("{noop}" + PASSWORD).roles("USER").build(),
        User.withUsername("bob").password("{noop}" + PASSWORD).roles("USER").build());
  }

  @Bean
  Gate gate() {
    return new Gate();
  }

  /** Lets tests hold a handler inside its execution deterministically. */
  public static final class Gate {
    private final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>();

    public void arm() {
      entered.set(new CountDownLatch(1));
      release.set(new CountDownLatch(1));
    }

    public void awaitEntered() throws InterruptedException {
      if (!entered.get().await(30, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("handler was not entered");
      }
    }

    public void open() {
      release.get().countDown();
    }

    void pass() throws InterruptedException {
      entered.get().countDown();
      if (!release.get().await(30, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("gate was not opened");
      }
    }
  }
}
