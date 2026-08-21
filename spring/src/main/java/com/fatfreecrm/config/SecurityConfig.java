package com.fatfreecrm.config;

import com.fatfreecrm.security.AuthlogicSha512PasswordEncoder;
import com.fatfreecrm.security.CrmAuthenticationProvider;
import com.fatfreecrm.security.JwtAuthenticationFilter;
import com.fatfreecrm.security.SecurityProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless security for {@code /api/v1}. The Rails app keeps its own cookie
 * session for the HTML UI, so nothing here touches Rails' session store.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Encoder used for hashes this service writes; legacy Rails hashes are handled separately. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthlogicSha512PasswordEncoder authlogicSha512PasswordEncoder(SecurityProperties properties) {
        return new AuthlogicSha512PasswordEncoder(properties.getLegacyStretches());
    }

    @Bean
    public AuthenticationManager authenticationManager(CrmAuthenticationProvider provider,
                                                       AuthenticationEventPublisher eventPublisher) {
        ProviderManager manager = new ProviderManager(provider);
        // Without a publisher, AuthenticationSuccessEvent never fires and Devise's
        // trackable columns would go stale for the still-live Rails UI.
        manager.setAuthenticationEventPublisher(eventPublisher);
        return manager;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // Mirrors the Rails app's permissive Access-Control-Allow-Origin: *.
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/openapi.yaml").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(handling -> handling.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .build();
    }
}
