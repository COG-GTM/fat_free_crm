package com.fatfreecrm.security;

import com.fatfreecrm.repository.GroupUserRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Stateless security chain: no sessions, no CSRF (pure JSON API behind a gateway), permissive
 * CORS (see {@code CorsConfig}), {@link TrustedHeaderAuthenticationFilter} as the sole
 * authentication mechanism for now. Everything under {@code /api/**} requires an authenticated
 * user; OpenAPI resources and the health probe are public.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    ProblemDetailAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemDetailAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    ProblemDetailAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return new ProblemDetailAccessDeniedHandler(objectMapper);
    }

    /**
     * The filter is deliberately NOT a Spring bean: Boot would otherwise also register it as a
     * plain servlet filter outside the security chain and run it twice per request.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserRepository userRepository,
            GroupUserRepository groupUserRepository,
            ProblemDetailAuthenticationEntryPoint entryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        TrustedHeaderAuthenticationFilter trustedHeaderAuthenticationFilter =
                new TrustedHeaderAuthenticationFilter(userRepository, groupUserRepository, entryPoint);
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/openapi.yaml", "/actuator/health", "/actuator/health/**", "/error")
                        .permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(trustedHeaderAuthenticationFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
