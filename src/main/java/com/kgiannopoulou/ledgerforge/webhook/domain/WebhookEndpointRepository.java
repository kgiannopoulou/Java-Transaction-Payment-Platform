package com.kgiannopoulou.ledgerforge.webhook.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository {

    void save(WebhookEndpoint endpoint, String secretCiphertext);

    Optional<WebhookEndpoint> findById(UUID id);

    /** Newest first, including disabled endpoints. */
    List<WebhookEndpoint> findByAccountId(UUID accountId);

    /** Active endpoints of these accounts that subscribed to {@code type}. */
    List<WebhookEndpoint> findActiveSubscribers(Collection<UUID> accountIds, WebhookEventType type);

    long countActive(UUID accountId);

    /** Returns {@code false} if the endpoint was already disabled. */
    boolean disable(UUID id, Instant now);
}
