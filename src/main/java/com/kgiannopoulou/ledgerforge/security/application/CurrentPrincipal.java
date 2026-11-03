package com.kgiannopoulou.ledgerforge.security.application;

import com.kgiannopoulou.ledgerforge.security.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of the caller, as established by the JWT filter. */
public final class CurrentPrincipal {

    private CurrentPrincipal() {
    }

    public static boolean hasAnyRole(Role... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> Arrays.stream(roles).map(Role::authority).anyMatch(authority::equals));
    }

    /** The caller's customer id, only for callers holding the CUSTOMER role. */
    public static Optional<UUID> customerId() {
        if (!hasAnyRole(Role.CUSTOMER)
                || !(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
            return Optional.empty();
        }
        String claim = jwt.getToken().getClaimAsString(JwtTokenIssuer.CUSTOMER_ID_CLAIM);
        try {
            return claim == null ? Optional.empty() : Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
