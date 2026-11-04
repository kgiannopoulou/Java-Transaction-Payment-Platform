package com.kgiannopoulou.ledgerforge.security.infrastructure;

import com.kgiannopoulou.ledgerforge.security.application.JwtProperties;
import com.kgiannopoulou.ledgerforge.security.application.JwtTokenIssuer;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT resource server. The rules below are the coarse role gates; per-resource
 * ownership is checked by {@code AccessPolicy} via {@code @PreAuthorize} on the controllers.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String SUPPORT = "SUPPORT";
    private static final String AUDITOR = "AUDITOR";
    private static final String ADMIN = "ADMIN";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                                    ProblemJsonSecurityHandlers problemHandlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // no cookies or sessions: bearer tokens are not sent automatically
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll() // dev profile only
                        .requestMatchers("/error").permitAll()

                        // onboarding and operator actions
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/accounts/*/deposits",
                                "/api/v1/accounts/*/freeze",
                                "/api/v1/accounts/*/unfreeze",
                                "/api/v1/accounts/*/close").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").hasAnyRole(CUSTOMER, ADMIN)

                        // only account holders move money
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers", "/api/v1/payments", "/api/v1/payments/**")
                        .hasRole(CUSTOMER)

                        // webhook configuration belongs to the account holder
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhook-endpoints", "/api/v1/webhook-deliveries/*/retry")
                        .hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/webhook-endpoints/*").hasRole(CUSTOMER)

                        // back-office reads
                        .requestMatchers(HttpMethod.GET, "/api/v1/ledger/**").hasAnyRole(SUPPORT, AUDITOR, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/risk/**").hasAnyRole(SUPPORT, AUDITOR, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/**").hasAnyRole(AUDITOR, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit-events", "/api/v1/audit-events/**")
                        .hasAnyRole(AUDITOR, ADMIN)

                        // resource reads: ownership decided per resource by @PreAuthorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()

                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenIssuer.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer())); // exp, nbf, iss
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    private static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
