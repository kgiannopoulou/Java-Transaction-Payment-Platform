package com.kgiannopoulou.ledgerforge.audit.application;

import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditEvent;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditEventRepository;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.shared.web.TraceIds;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Records audit events. {@link Propagation#MANDATORY} makes the guarantee structural: an event
 * can only be written inside the caller's business transaction, so it commits together with
 * the change it describes, or not at all.
 */
@Service
public class AuditTrail {

    static final String SYSTEM_ACTOR = "system";

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditTrail(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, AuditedEntity entityType, UUID entityId, Map<String, Object> metadata) {
        repository.append(new AuditEvent(UUID.randomUUID(), currentActor(), action, entityType, entityId,
                clock.instant(), MDC.get(TraceIds.MDC_KEY), metadata));
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(AuditEventRepository.Query query, Pageable pageable) {
        return repository.search(query, pageable);
    }

    /** The JWT subject of the caller; {@code system} for scheduled jobs and consumers. */
    static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return SYSTEM_ACTOR;
        }
        return authentication.getName();
    }
}
