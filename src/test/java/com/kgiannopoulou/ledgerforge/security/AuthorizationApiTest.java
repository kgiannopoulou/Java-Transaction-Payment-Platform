package com.kgiannopoulou.ledgerforge.security;

import com.kgiannopoulou.ledgerforge.security.application.JwtProperties;
import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security is tested, not just configured: every rule in the role table and every ownership
 * check is exercised over real HTTP with real signed tokens.
 */
class AuthorizationApiTest extends IntegrationTest {

    @Autowired
    JwtProperties jwtProperties;

    UUID alice;
    UUID bob;
    UUID aliceWallet;
    UUID bobWallet;

    @BeforeEach
    void aliceAndBob() {
        aliceWallet = newFundedWallet("EUR", "100.00");
        bobWallet = newFundedWallet("EUR", "50.00");
        alice = ownerOf(aliceWallet);
        bob = ownerOf(bobWallet);
    }

    @Nested
    class Customers {

        @Test
        void aliceCannotReadBobsAccount() {
            var response = asCustomer(alice).get("/api/v1/accounts/" + bobWallet);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.code()).isEqualTo("ACCESS_DENIED");
            assertThat(response.body()).doesNotContainKey("balance");
            assertThat(asCustomer(bob).get("/api/v1/accounts/" + bobWallet).status()).isEqualTo(200);
        }

        @Test
        void aliceCannotReadBobsTransactionsOrProfileOrWalletList() {
            var client = asCustomer(alice);
            assertThat(client.get("/api/v1/accounts/" + bobWallet + "/transactions").status()).isEqualTo(403);
            assertThat(client.get("/api/v1/customers/" + bob).status()).isEqualTo(403);
            assertThat(client.get("/api/v1/customers/" + bob + "/accounts").status()).isEqualTo(403);

            assertThat(client.get("/api/v1/accounts/" + aliceWallet + "/transactions").status()).isEqualTo(200);
            assertThat(client.get("/api/v1/customers/" + alice).status()).isEqualTo(200);
            assertThat(client.get("/api/v1/customers/" + alice + "/accounts").status()).isEqualTo(200);
        }

        @Test
        void aliceCannotMoveMoneyOutOfBobsWallet() {
            var stolen = asCustomer(alice).post("/api/v1/transfers", Map.of(
                    "sourceAccountId", bobWallet, "destinationAccountId", aliceWallet,
                    "amount", "50.00", "currency", "EUR"));

            assertThat(stolen.status()).isEqualTo(403);
            assertThat(balanceOf(bobWallet)).isEqualByComparingTo("50.00");
            assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("100.00");
        }

        @Test
        void unknownIdsAreForbiddenForCustomersButNotFoundForStaff() {
            UUID unknown = UUID.randomUUID();
            // A customer cannot tell "exists but not yours" from "does not exist".
            assertThat(asCustomer(alice).get("/api/v1/accounts/" + unknown).status()).isEqualTo(403);
            assertThat(as("sam", Role.SUPPORT).get("/api/v1/accounts/" + unknown).status()).isEqualTo(404);
        }

        @Test
        void customersOpenWalletsOnlyForThemselves() {
            var own = asCustomer(alice).post("/api/v1/accounts", Map.of("customerId", alice, "currency", "USD"));
            assertThat(own.status()).isEqualTo(201);

            var forBob = asCustomer(alice).post("/api/v1/accounts", Map.of("customerId", bob, "currency", "USD"));
            assertThat(forBob.status()).isEqualTo(403);
        }

        @Test
        void transfersAreVisibleToBothParticipantsOnly() {
            var transfer = transfer(aliceWallet, bobWallet, "10.00", "EUR");
            assertThat(transfer.status()).isEqualTo(201);
            String path = "/api/v1/transfers/" + transfer.string("id");

            assertThat(asCustomer(alice).get(path).status()).isEqualTo(200);
            assertThat(asCustomer(bob).get(path).status()).isEqualTo(200);
            assertThat(asCustomer(newCustomer()).get(path).status()).isEqualTo(403);
        }

        @Test
        void paymentCommandsBelongToTheRightSide() {
            UUID payment = newPayment(aliceWallet, bobWallet, "20.00", "EUR");
            String base = "/api/v1/payments/" + payment;

            assertThat(asCustomer(bob).post(base + "/authorize", null).status()).isEqualTo(403);   // merchant
            assertThat(asCustomer(alice).post(base + "/authorize", null).status()).isEqualTo(200); // payer
            assertThat(asCustomer(alice).post(base + "/capture", null).status()).isEqualTo(403);   // payer
            assertThat(asCustomer(bob).post(base + "/capture", null).status()).isEqualTo(200);     // merchant
            assertThat(asCustomer(alice).post(base + "/refund", Map.of("amount", "5.00", "currency", "EUR"))
                    .status()).isEqualTo(403);
            assertThat(asCustomer(newCustomer()).get(base).status()).isEqualTo(403);
            assertThat(asCustomer(alice).get(base).status()).isEqualTo(200);
        }

        @Test
        void customersCannotUseOperatorEndpoints() {
            var client = asCustomer(alice);
            assertThat(client.post("/api/v1/accounts/" + aliceWallet + "/deposits",
                    Map.of("amount", "1000.00", "currency", "EUR")).status()).isEqualTo(403);
            assertThat(client.post("/api/v1/accounts/" + aliceWallet + "/unfreeze", null).status()).isEqualTo(403);
            assertThat(client.post("/api/v1/customers", Map.of("email", "x@example.com", "fullName", "X"))
                    .status()).isEqualTo(403);
            assertThat(client.get("/api/v1/audit-events").status()).isEqualTo(403);
            assertThat(client.get("/api/v1/admin/reconciliation").status()).isEqualTo(403);
            assertThat(client.get("/api/v1/ledger/transactions/" + UUID.randomUUID()).status()).isEqualTo(403);
            assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    class Staff {

        @Test
        void supportInspectsButCannotAlterAnything() {
            var support = as("sam", Role.SUPPORT);
            var deposit = api.post("/api/v1/accounts/" + aliceWallet + "/deposits",
                    Map.of("amount", "1.00", "currency", "EUR"));

            assertThat(support.get("/api/v1/accounts/" + aliceWallet).status()).isEqualTo(200);
            assertThat(support.get("/api/v1/accounts/" + aliceWallet + "/transactions").status()).isEqualTo(200);
            assertThat(support.get("/api/v1/customers/" + alice).status()).isEqualTo(200);
            assertThat(support.get("/api/v1/ledger/transactions/" + deposit.string("ledgerTransactionId")).status())
                    .isEqualTo(200);

            assertThat(support.post("/api/v1/transfers", Map.of("sourceAccountId", aliceWallet,
                    "destinationAccountId", bobWallet, "amount", "1.00", "currency", "EUR")).status()).isEqualTo(403);
            assertThat(support.post("/api/v1/accounts/" + aliceWallet + "/deposits",
                    Map.of("amount", "1.00", "currency", "EUR")).status()).isEqualTo(403);
            assertThat(support.post("/api/v1/accounts/" + aliceWallet + "/freeze", null).status()).isEqualTo(403);
            assertThat(support.get("/api/v1/audit-events").status()).isEqualTo(403);
            assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("101.00");
        }

        @Test
        void auditorReadsLedgerAndAuditButModifiesNothingAndSeesNoProfiles() {
            var auditor = as("ava", Role.AUDITOR);
            var deposit = api.post("/api/v1/accounts/" + aliceWallet + "/deposits",
                    Map.of("amount", "1.00", "currency", "EUR"));

            assertThat(auditor.get("/api/v1/ledger/transactions/" + deposit.string("ledgerTransactionId")).status())
                    .isEqualTo(200);
            assertThat(auditor.get("/api/v1/accounts/" + aliceWallet + "/transactions").status()).isEqualTo(200);
            assertThat(auditor.get("/api/v1/admin/reconciliation").status()).isEqualTo(200);
            assertThat(auditor.get("/api/v1/audit-events?entityId=" + aliceWallet).status()).isEqualTo(200);

            assertThat(auditor.get("/api/v1/customers/" + alice).status()).isEqualTo(403);
            assertThat(auditor.post("/api/v1/accounts/" + aliceWallet + "/freeze", null).status()).isEqualTo(403);
            assertThat(auditor.post("/api/v1/accounts/" + aliceWallet + "/deposits",
                    Map.of("amount", "1.00", "currency", "EUR")).status()).isEqualTo(403);
            assertThat(auditor.post("/api/v1/customers", Map.of("email", "y@example.com", "fullName", "Y"))
                    .status()).isEqualTo(403);
        }

        @Test
        void adminAdministersButCannotSpendCustomersMoney() {
            assertThat(api.post("/api/v1/accounts/" + bobWallet + "/freeze", null).status()).isEqualTo(200);
            assertThat(api.post("/api/v1/accounts/" + bobWallet + "/unfreeze", null).status()).isEqualTo(200);

            var transfer = api.post("/api/v1/transfers", Map.of("sourceAccountId", aliceWallet,
                    "destinationAccountId", bobWallet, "amount", "1.00", "currency", "EUR"));
            assertThat(transfer.status()).isEqualTo(403);
            assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    class Authentication {

        @Test
        void missingTokenIs401InTheStandardErrorShape() {
            var response = anonymous.get("/api/v1/accounts/" + aliceWallet);

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.body()).containsKeys("title", "status", "detail", "traceId");
            assertThat(response.header("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).startsWith("Bearer"));
        }

        @Test
        void healthIsPublic() {
            assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
        }

        @Test
        void tokenSignedWithAnotherKeyIsRejected() {
            String forged = token("another-secret-that-is-at-least-32-bytes!!", jwtProperties.issuer(),
                    Instant.now().plus(Duration.ofHours(1)));
            assertThat(anonymous.withToken(forged).get("/api/v1/accounts/" + aliceWallet).status()).isEqualTo(401);
        }

        @Test
        void expiredTokenIsRejected() {
            String expired = token(jwtProperties.secret(), jwtProperties.issuer(), Instant.now().minus(Duration.ofHours(1)));
            assertThat(anonymous.withToken(expired).get("/api/v1/accounts/" + aliceWallet).status()).isEqualTo(401);
        }

        @Test
        void tokenFromAnotherIssuerIsRejected() {
            String foreign = token(jwtProperties.secret(), "someone-else", Instant.now().plus(Duration.ofHours(1)));
            assertThat(anonymous.withToken(foreign).get("/api/v1/accounts/" + aliceWallet).status()).isEqualTo(401);
        }

        @Test
        void garbageTokenIsRejected() {
            assertThat(anonymous.withToken("not.a.jwt").get("/api/v1/accounts/" + aliceWallet).status()).isEqualTo(401);
        }

        @Test
        void validTokenWithoutRolesIsAuthenticatedButForbidden() {
            ApiClient noRoles = anonymous.withToken(tokens.issue("nobody", List.of(), null));
            assertThat(noRoles.get("/api/v1/accounts/" + aliceWallet).status()).isEqualTo(403);
            assertThat(noRoles.post("/api/v1/transfers", Map.of()).status()).isEqualTo(403);
        }

        /** Alice's identity, but signed/issued/timed as specified. */
        private String token(String secret, String issuer, Instant expiresAt) {
            var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            var claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject(alice.toString())
                    .issuedAt(expiresAt.minus(Duration.ofHours(2)))
                    .expiresAt(expiresAt)
                    .claim("roles", List.of("CUSTOMER"))
                    .claim("customer_id", alice.toString())
                    .build();
            return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                    .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();
        }
    }
}
