package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ModelApproval;
import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ModelApproval} — the workflow for granting additional
 * models to a virtual key. An APPROVED approval inserts the model into
 * {@code virtual_key_models}, which the gateway snapshot picks up within one
 * refresh interval.
 */
public interface ModelApprovalRepository {

    ModelApproval insert(ModelApproval approval);

    Optional<ModelApproval> findById(UUID id);

    List<ModelApproval> findAllByVirtualKeyId(UUID virtualKeyId);

    /**
     * Approvals requested by one user (oldest first is NOT guaranteed; newest
     * first).
     */
    List<ModelApproval> findAllByRequestedBy(UUID requestedBy);

    /**
     * Page of approvals ordered newest-first ({@code created_at DESC, id DESC}),
     * filtered by {@code status} when non-null. Keyset pagination: pass the
     * {@code created_at}/{@code id} of the last item as {@code beforeCreatedAt}/
     * {@code beforeId} (both null = first page). The caller clamps {@code limit}.
     */
    List<ModelApproval> findPage(ModelApprovalStatus status, int limit, Instant beforeCreatedAt, UUID beforeId);

    List<ModelApproval> findAllByStatus(ModelApprovalStatus status);

    ModelApproval update(ModelApproval approval);
}
