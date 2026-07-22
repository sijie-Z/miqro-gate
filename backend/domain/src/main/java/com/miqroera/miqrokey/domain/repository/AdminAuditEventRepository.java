package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.AdminAuditEvent;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link AdminAuditEvent} append-only entities.
 */
public interface AdminAuditEventRepository {

    AdminAuditEvent insert(AdminAuditEvent event);

    List<AdminAuditEvent> findByTargetTypeAndTargetId(String targetType, UUID targetId);

    List<AdminAuditEvent> findByActorId(UUID actorId, int limit);
}
