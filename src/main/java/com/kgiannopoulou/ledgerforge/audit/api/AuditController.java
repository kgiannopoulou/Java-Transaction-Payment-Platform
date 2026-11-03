package com.kgiannopoulou.ledgerforge.audit.api;

import com.kgiannopoulou.ledgerforge.audit.application.AuditTrail;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditEvent;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditEventRepository;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only access to the audit trail (AUDITOR, ADMIN; enforced in the security config). */
@RestController
@Validated
class AuditController {

    private final AuditTrail auditTrail;

    AuditController(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @GetMapping("/api/v1/audit-events")
    PageResponse<AuditEvent> search(
            @RequestParam(required = false) @Nullable AuditedEntity entityType,
            @RequestParam(required = false) @Nullable UUID entityId,
            @RequestParam(required = false) @Nullable String actor,
            @RequestParam(required = false) @Nullable AuditAction action,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var query = new AuditEventRepository.Query(entityType, entityId, actor, action);
        return PageResponse.from(auditTrail.search(query, PageRequest.of(page, size)));
    }
}
