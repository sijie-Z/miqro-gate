package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ModelApproval;
import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;
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

    List<ModelApproval> findAllByStatus(ModelApprovalStatus status);

    ModelApproval update(ModelApproval approval);
}
