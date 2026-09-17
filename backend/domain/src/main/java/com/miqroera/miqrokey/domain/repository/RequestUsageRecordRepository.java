package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.ModelCallRecord;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side access to {@code request_usage_records} (the per-request lifecycle
 * audit trail).
 *
 * <p>
 * The table is written by the gateway's usage writer; this repository is the
 * first read path over it, added for the model-call timeline (#705).
 * </p>
 */
public interface RequestUsageRecordRepository {

    /**
     * Finds the lifecycle record of one forwarded call.
     *
     * <p>
     * The lookup is tenant-scoped: a {@code gatewayRequestId} from another tenant
     * must not be readable, and the caller cannot tell "belongs to someone else"
     * apart from "does not exist".
     * </p>
     *
     * @param tenantId
     *            tenant boundary; required
     * @param gatewayRequestId
     *            the gateway-issued request id shown in usage records
     * @return the record, or empty when the id is unknown in this tenant (or the
     *         call never reached upstream — cache hits and auth/model rejections
     *         are not written to this table)
     */
    Optional<ModelCallRecord> findByGatewayRequestId(UUID tenantId, String gatewayRequestId);
}
