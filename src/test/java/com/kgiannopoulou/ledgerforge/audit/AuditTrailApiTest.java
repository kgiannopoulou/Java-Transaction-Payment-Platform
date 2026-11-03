package com.kgiannopoulou.ledgerforge.audit;

import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTrailApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void transferIsAuditedWithActorTraceIdAndDetails() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");

        var transfer = transfer(alice, bob, "25.00", "EUR");
        String transferId = transfer.string("id");

        var events = auditEvents("entityId=" + transferId);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event)
                .containsEntry("action", "TRANSFER_CREATED")
                .containsEntry("entityType", "TRANSFER")
                .containsEntry("actor", ownerOf(alice).toString())
                .containsEntry("traceId", transfer.header("X-Trace-Id").orElseThrow());
        assertThat(metadata(event))
                .containsEntry("sourceAccountId", alice.toString())
                .containsEntry("destinationAccountId", bob.toString())
                .containsEntry("amount", "25.00")
                .containsEntry("currency", "EUR");
    }

    @Test
    void operatorActionsRecordTheOperator() {
        UUID wallet = newWallet("EUR");
        api.post("/api/v1/accounts/" + wallet + "/freeze", null);
        api.post("/api/v1/accounts/" + wallet + "/unfreeze", null);

        // newest first
        assertThat(auditEvents("entityId=" + wallet)).extracting(e -> e.get("action"))
                .containsExactly("ACCOUNT_UNFROZEN", "ACCOUNT_FROZEN", "ACCOUNT_CREATED");
        assertThat(auditEvents("entityId=" + wallet + "&action=ACCOUNT_FROZEN"))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("actor", "test-admin"));
    }

    @Test
    void paymentLifecycleIsFullyAudited() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "40.00", "EUR");
        asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/capture", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/refund", Map.of("amount", "40.00", "currency", "EUR"));

        assertThat(auditEvents("entityType=PAYMENT&entityId=" + payment)).extracting(e -> e.get("action"))
                .containsExactly("PAYMENT_REFUNDED", "PAYMENT_CAPTURED", "PAYMENT_AUTHORIZED", "PAYMENT_CREATED");
    }

    @Test
    void rejectedOperationsLeaveNoAuditEvent() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");

        assertThat(transfer(alice, bob, "500.00", "EUR").status()).isEqualTo(422);

        // The audit insert is part of the business transaction, so it rolled back with it.
        long count = jdbc.sql("SELECT count(*) FROM audit_events WHERE metadata ->> 'sourceAccountId' = ?")
                .param(alice.toString()).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    void auditEventsCannotBeUpdatedDeletedOrTruncated() {
        UUID wallet = newWallet("EUR");
        UUID eventId = jdbc.sql("SELECT id FROM audit_events WHERE entity_id = ?").param(wallet)
                .query(UUID.class).single();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET actor = 'mallory' WHERE id = ?").param(eventId).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events WHERE id = ?").param(eventId).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_events").update())
                .hasMessageContaining("append-only");

        assertThat(jdbc.sql("SELECT actor FROM audit_events WHERE id = ?").param(eventId).query(String.class).single())
                .isEqualTo("test-admin");
    }

    @Test
    void onlyAuditorsAndAdminsCanReadTheTrail() {
        UUID wallet = newWallet("EUR");
        assertThat(as("ava", Role.AUDITOR).get("/api/v1/audit-events?entityId=" + wallet).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/audit-events?entityId=" + wallet).status()).isEqualTo(200);
        assertThat(as("sam", Role.SUPPORT).get("/api/v1/audit-events").status()).isEqualTo(403);
        assertThat(asOwnerOf(wallet).get("/api/v1/audit-events").status()).isEqualTo(403);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> auditEvents(String query) {
        var response = as("ava", Role.AUDITOR).get("/api/v1/audit-events?" + query);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(200);
        return (List<Map<String, Object>>) response.body().get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> event) {
        return (Map<String, Object>) event.get("metadata");
    }
}
