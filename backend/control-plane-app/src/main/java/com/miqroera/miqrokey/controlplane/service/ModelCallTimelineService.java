package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ModelCallTimelineView;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.RequestUsageRecordRepository;
import com.miqroera.miqrokey.domain.usage.ModelCallRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Assembles the model-call timeline (#705) from the lifecycle audit trail.
 *
 * <p>
 * Pure read + shape: no new collection, no content. The gateway already records
 * every milestone ({@code started_at} / {@code first_byte_at} /
 * {@code completed_at}); this service turns one row into the phase list the
 * console replays.
 * </p>
 *
 * <p>
 * Lookups are tenant-scoped, and an unknown id is reported the same way whether
 * it never existed or belongs to another tenant — the endpoint must not become
 * an existence oracle for request ids.
 * </p>
 */
@Service
public class ModelCallTimelineService {

    private static final String KEY_ACCEPTED = "ACCEPTED";
    private static final String KEY_FIRST_BYTE = "FIRST_BYTE";
    private static final String KEY_COMPLETED = "COMPLETED";

    private final RequestUsageRecordRepository recordRepository;

    public ModelCallTimelineService(RequestUsageRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * Builds the timeline of one forwarded call.
     *
     * @throws ApiException
     *             {@code NOT_FOUND} when the id is unknown in this tenant. The
     *             lifecycle table only covers calls that actually reached upstream:
     *             coalesced requests write {@code usage_event} but no lifecycle
     *             row, and cache hits go to {@code cache_hit_event} only — neither
     *             can be replayed here.
     */
    public ModelCallTimelineView timeline(User user, String gatewayRequestId) {
        if (gatewayRequestId == null || gatewayRequestId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REQUIRED", "请提供 gatewayRequestId。");
        }
        ModelCallRecord record = recordRepository.findByGatewayRequestId(user.tenantId(), gatewayRequestId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND",
                        "未找到该请求的调用记录。调用留痕只覆盖实际转发到上游的请求——" + "合并（coalesced）请求与缓存命中不写入该表。"));

        return new ModelCallTimelineView(record.gatewayRequestId(), record.upstreamRequestId(), record.modelId(),
                record.wireProtocol(), record.streaming(), record.requestStatus(), record.httpStatus(),
                record.clientCancelled(), record.partialResponse(), record.retryCount(), record.startedAt(),
                record.firstByteAt(), record.completedAt(), record.durationMs(), record.timeToFirstByteMs(),
                new ModelCallTimelineView.Tokens(record.inputTokens(), record.outputTokens(),
                        record.cacheReadInputTokens(), record.cacheCreationInputTokens()),
                new ModelCallTimelineView.Attribution(record.userId(), record.projectId(), record.virtualKeyId(),
                        record.providerId(), record.providerProductId(), record.credentialId()),
                phases(record));
    }

    /**
     * The milestones actually observed. A call that was cancelled before the
     * upstream answered has an {@code ACCEPTED} phase only — the absence of a later
     * phase is itself the diagnosis, so nothing is synthesized.
     */
    private static List<ModelCallTimelineView.Phase> phases(ModelCallRecord record) {
        List<ModelCallTimelineView.Phase> phases = new ArrayList<>(3);
        Instant startedAt = record.startedAt();
        phases.add(new ModelCallTimelineView.Phase(KEY_ACCEPTED, "受理", startedAt, 0L));

        if (record.firstByteAt() != null) {
            phases.add(new ModelCallTimelineView.Phase(KEY_FIRST_BYTE, "上游首字节", record.firstByteAt(),
                    elapsedMs(startedAt, record.firstByteAt(), record.timeToFirstByteMs())));
        }
        if (record.completedAt() != null) {
            phases.add(new ModelCallTimelineView.Phase(KEY_COMPLETED, "完成", record.completedAt(),
                    elapsedMs(startedAt, record.completedAt(), record.durationMs())));
        }
        return phases;
    }

    /**
     * Prefers the gateway's own measurement; falls back to the timestamp delta when
     * a value is missing (e.g. rows written before a metric existed).
     */
    private static Long elapsedMs(Instant startedAt, Instant at, Long measured) {
        if (measured != null) {
            return measured;
        }
        if (startedAt == null || at == null) {
            return null;
        }
        return Duration.between(startedAt, at).toMillis();
    }
}
