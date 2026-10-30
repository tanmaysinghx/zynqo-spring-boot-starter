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
package io.github.tanmaysinghx.zynqo.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic with two demo users. Zynqo scopes idempotency keys by the authenticated principal, so
 * alice and bob can never see each other's stored responses.
 *
 * <p>The demo password is supplied through {@code DEMO_PASSWORD}; there is no default.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean
  UserDetailsService users(@Value("${demo.password}") String password) {
    PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("alice").password(encoder.encode(password)).roles("USER").build(),
        User.withUsername("bob").password(encoder.encode(password)).roles("USER").build());
  }
}
