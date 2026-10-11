package com.vendor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Two areas: the check-in API, which client portals call with their own token (checked in
 * CheckInController) and Paystack's webhook (signature checked); and the pages, which need a vendor
 * signed in with an emailed code (LoginController; CSRF on), except the public pay pages behind each
 * invoice's secret link.
 */
@Configuration
public class SecurityConfig {

    /** There are no passwords; this only stops Spring Boot from making a default user with one. */
    @Bean
    public UserDetailsService noPasswordAccounts() {
        return username -> { throw new UsernameNotFoundException("Sign in with an emailed code."); };
    }

    @Bean
    @Order(1)
    public SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain pages(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                    .requestMatchers("/login", "/login/**", "/css/**", "/js/**", "/favicon.svg", "/error", "/pay/**").permitAll()
                    .anyRequest().hasRole("VENDOR"))
            .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?signedout"))
            .headers(h -> h
                    .frameOptions(fo -> fo.deny())
                    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                    .contentSecurityPolicy(c -> c.policyDirectives(
                            "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; form-action 'self' https://checkout.paystack.com; frame-ancestors 'none'")));
        return http.build();
    }
}
