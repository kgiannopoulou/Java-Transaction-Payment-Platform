package com.kgiannopoulou.ledgerforge.security.application;

import com.kgiannopoulou.ledgerforge.security.domain.Role;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Issues signed access tokens. In a real deployment an identity provider does this; here it
 * backs the dev token endpoint and the tests.
 *
 * <p>Claims: {@code sub} (customer id or staff username), {@code roles}, and for customers
 * {@code customer_id}, which ownership checks compare against.
 */
@Service
public class JwtTokenIssuer {

    public static final String ROLES_CLAIM = "roles";
    public static final String CUSTOMER_ID_CLAIM = "customer_id";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenIssuer(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String forCustomer(UUID customerId) {
        return issue(customerId.toString(), List.of(Role.CUSTOMER), customerId);
    }

    public String forStaff(String username, Role... roles) {
        return issue(username, List.of(roles), null);
    }

    public String issue(String subject, Collection<Role> roles, @Nullable UUID customerId) {
        if (roles.contains(Role.CUSTOMER) && customerId == null) {
            throw new IllegalArgumentException("CUSTOMER tokens need a customer id");
        }
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .id(UUID.randomUUID().toString())
                .claim(ROLES_CLAIM, roles.stream().map(Role::name).sorted().toList());
        if (customerId != null) {
            claims.claim(CUSTOMER_ID_CLAIM, customerId.toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }
}
