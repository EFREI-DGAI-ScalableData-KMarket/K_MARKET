package com.kmarket.ordersservice.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Secures the service as a JWT resource server. Tokens are the HS256 access
 * tokens minted by auth-service and signed with the shared {@code JWT_SECRET}
 * (same convention as stock-service / catalog-service). The {@code roles} claim
 * is mapped to Spring Security {@code ROLE_*} authorities.
 */
@Configuration
public class SecurityConfig {

    private static final String ROLES_CLAIM = "roles";

    private final String jwtSecret;

    public SecurityConfig(@Value("${app.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (context-path /v1 is stripped from these matchers)
                        .requestMatchers("/health").permitAll()
                        // Swagger UI + OpenAPI docs
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-resources/**", "/webjars/**").permitAll()
                        // --- Role rules (RBAC), aligned with the platform role model ---
                        // Status change is internal / logistics only.
                        .requestMatchers(HttpMethod.PATCH, "/orders/*/status")
                                .hasAnyRole("logistics", "admin", "service_account")
                        // Listing all orders is a back-office read (ownership still scopes
                        // non-privileged callers to their own orders in the controller).
                        .requestMatchers(HttpMethod.GET, "/orders")
                                .hasAnyRole("admin", "analyst", "logistics", "service_account")
                        // Creating and reading a single order: any authenticated caller;
                        // resource ownership is enforced in the controller (OrderOwnership).
                        .requestMatchers(HttpMethod.POST, "/orders").authenticated()
                        .requestMatchers(HttpMethod.GET, "/orders/*").authenticated()
                        // Everything else needs a valid JWT
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "Jeton absent, invalide ou expiré"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "Rôle insuffisant")));

        return http.build();
    }

    /** Validates HS256 tokens signed with the shared secret. */
    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> validator =
                JwtValidators.createDefault();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** Maps the token's {@code roles} claim (a list of names) to {@code ROLE_*} authorities. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** Writes an {@code Error}-schema JSON body. */
    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = """
                {"code":"%s","message":"%s","request_id":"%s"}""".formatted(code, message, UUID.randomUUID());
        response.getWriter().write(body);
    }
}
