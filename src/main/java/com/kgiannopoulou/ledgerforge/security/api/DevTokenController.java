package com.kgiannopoulou.ledgerforge.security.api;

import com.kgiannopoulou.ledgerforge.security.application.JwtTokenIssuer;
import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.shared.error.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * Mints tokens for local development and demos ({@code ledgerforge.security.dev-tokens.enabled},
 * off by default and enabled only in {@code compose.yaml}). A real deployment gets tokens from an
 * identity provider and never exposes this endpoint.
 */
@RestController
@ConditionalOnProperty(prefix = "ledgerforge.security.dev-tokens", name = "enabled", havingValue = "true")
class DevTokenController {

    private final JwtTokenIssuer issuer;

    DevTokenController(JwtTokenIssuer issuer) {
        this.issuer = issuer;
    }

    record DevTokenRequest(@NotBlank @Size(max = 100) String subject, @NotEmpty Set<Role> roles,
                           @Nullable UUID customerId) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    @PostMapping("/api/v1/auth/token")
    TokenResponse token(@Valid @RequestBody DevTokenRequest request) {
        if (request.roles().contains(Role.CUSTOMER) && request.customerId() == null) {
            throw new InvalidRequestException("CUSTOMER_ID_REQUIRED", "CUSTOMER tokens need a customerId");
        }
        String token = request.roles().contains(Role.CUSTOMER)
                ? issuer.issue(request.customerId().toString(), request.roles(), request.customerId())
                : issuer.issue(request.subject(), request.roles(), null);
        return new TokenResponse(token, "Bearer", issuer.ttlSeconds());
    }
}
