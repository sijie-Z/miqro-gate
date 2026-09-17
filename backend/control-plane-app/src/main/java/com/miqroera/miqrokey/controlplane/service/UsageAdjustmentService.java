package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsageAdjustmentRequest;
import com.miqroera.miqrokey.controlplane.dto.UsageAdjustmentView;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.UsageAdjustmentRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.AdjustmentTarget;
import com.miqroera.miqrokey.domain.usage.AdjustmentType;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.UsageAdjustment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends and reads usage adjustments (#709 / backlog F20).
 *
 * <p>
 * The observed fact is never touched. A correction is a new row; correcting the
 * correction is another new row (a reversal). Nothing in this service issues an
 * UPDATE or a DELETE against usage.
 * </p>
 *
 * <p>
 * Scope note: adjustments feed the <b>financial/reporting</b> reading of usage
 * (detail, aggregates, billing, export). Quota enforcement deliberately keeps
 * reading observed usage only — a financial correction must not retroactively
 * rewrite what the runtime already decided, or a back-dated correction would
 * silently un-break a key that was blocked for exceeding its quota.
 * </p>
 */
@Service
public class UsageAdjustmentService {

    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_REASON_CODE_LENGTH = 32;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final UsageAdjustmentRepository repository;
    private final AuditService auditService;

    public UsageAdjustmentService(UsageAdjustmentRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * Appends one correction, or one reversal, against a usage event.
     *
     * <p>
     * Runs under a per-event advisory lock: the "net must not go negative" rule is
     * a read-then-write, so two concurrent submissions would otherwise each see a
     * healthy net and jointly overshoot it.
     * </p>
     *
     * @throws ApiException
     *             {@code NOT_FOUND} when the request id is unknown in this tenant;
     *             {@code BAD_REQUEST} for an unusable adjustment (no delta, a
     *             reversal of a reversal, a net that would go negative)
     */
    @Transactional
    public UsageAdjustmentView append(User user, UsageAdjustmentRequest request, AuditContext audit) {
        UUID tenantId = user.tenantId();
        String gatewayRequestId = requireText(request.gatewayRequestId(), "REQUEST_ID_REQUIRED",
                "请提供 gatewayRequestId。");
        String reason = requireText(request.reason(), "REASON_REQUIRED", "请填写调整原因。");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG", "调整原因过长。");
        }
        String reasonCode = trimToNull(request.reasonCode());
        if (reasonCode != null && reasonCode.length() > MAX_REASON_CODE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_CODE_TOO_LONG", "原因代码过长。");
        }
        String idempotencyKey = trimToNull(request.idempotencyKey());
        if (idempotencyKey != null && idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_TOO_LONG", "幂等键过长。");
        }

        UUID usageEventId = repository.findUsageEventIdByGatewayRequestId(tenantId, gatewayRequestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USAGE_EVENT_NOT_FOUND", "未找到该请求的用量记录。"));

        repository.lockUsageEvent(usageEventId);

        if (idempotencyKey != null) {
            Optional<UsageAdjustment> existing = repository.findByIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                return view(existing.get());
            }
        }

        AdjustmentTarget target = repository.findAdjustmentTarget(tenantId, usageEventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USAGE_EVENT_NOT_FOUND", "未找到该请求的用量记录。"));

        UsageAdjustment adjustment = request.reversalOfId() != null
                ? reversalOf(tenantId, target, request.reversalOfId(), reason, reasonCode, idempotencyKey)
                : correctionOf(tenantId, target, request, reason, reasonCode, idempotencyKey);

        UsageAdjustment stored = repository.append(adjustment);
        UUID actorId = audit == null ? user.id() : audit.actorId();
        auditService.record(tenantId, actorId,
                stored.isReversal() ? "USAGE_ADJUSTMENT_REVERSED" : "USAGE_ADJUSTMENT_CREATED", "USAGE_ADJUSTMENT",
                stored.id(), summary(stored), audit == null ? null : audit.requestId());
        return view(stored);
    }

    /**
     * Every adjustment booked against the usage event behind one request id, oldest
     * first.
     *
     * @throws ApiException
     *             {@code NOT_FOUND} when the request id is unknown in this tenant
     */
    public List<UsageAdjustmentView> listForRequest(User user, String gatewayRequestId) {
        String requestId = requireText(gatewayRequestId, "REQUEST_ID_REQUIRED", "请提供 gatewayRequestId。");
        UUID usageEventId = repository.findUsageEventIdByGatewayRequestId(user.tenantId(), requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USAGE_EVENT_NOT_FOUND", "未找到该请求的用量记录。"));
        return repository.findByUsageEventId(user.tenantId(), usageEventId).stream().map(UsageAdjustmentService::view)
                .toList();
    }

    private UsageAdjustment correctionOf(UUID tenantId, AdjustmentTarget target, UsageAdjustmentRequest request,
            String reason, String reasonCode, String idempotencyKey) {
        Long input = request.inputTokensDelta();
        Long output = request.outputTokensDelta();
        Long cacheRead = request.cacheReadTokensDelta();
        Long cacheCreation = request.cacheCreationTokensDelta();
        if (!anyNonZeroDelta(input, output, cacheRead, cacheCreation)) {
            // Covers both "nothing supplied" and "supplied but all zeroes": neither
            // states a correction, and recording it would only add noise to an
            // append-only ledger that cannot be tidied up afterwards.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADJUSTMENT_EMPTY", "请至少提供一个非 0 的 token 增减量。");
        }
        if (target.cacheLevel() == CacheLevel.L1_HIT || target.cacheLevel() == CacheLevel.L2_HIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADJUSTMENT_TARGET_HAS_NO_USAGE", "缓存命中行不承载用量，无法调整。");
        }
        assertNetStaysNonNegative(target, input, output, cacheRead, cacheCreation);

        return new UsageAdjustment(UUID.randomUUID(), tenantId, target.usageEventId(), AdjustmentType.USAGE, input,
                output, cacheRead, cacheCreation, null, null, reason, reasonCode, null, null, null, Instant.now(),
                idempotencyKey);
    }

    /**
     * Builds the row that undoes an earlier one. The deltas are taken from the row
     * being undone and negated, never from the request, so a reversal cannot
     * disagree with what it reverses.
     */
    private UsageAdjustment reversalOf(UUID tenantId, AdjustmentTarget target, UUID reversalOfId, String reason,
            String reasonCode, String idempotencyKey) {
        UsageAdjustment original = repository.findById(tenantId, reversalOfId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ADJUSTMENT_NOT_FOUND", "未找到要撤销的调整记录。"));
        if (!original.usageEventId().equals(target.usageEventId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVERSAL_TARGET_MISMATCH", "要撤销的调整不属于该用量记录。");
        }
        if (original.isReversal()) {
            // Reversing a reversal would rebuild the chain the ledger deliberately
            // avoids; record a fresh correction instead.
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVERSAL_OF_REVERSAL", "不能撤销一笔撤销记录，请改为登记一笔新的调整。");
        }
        if (original.adjustmentType() != AdjustmentType.USAGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVERSAL_UNSUPPORTED", "金额维度的调整尚未开放，无可撤销内容。");
        }
        Long input = negate(original.inputTokensDelta());
        Long output = negate(original.outputTokensDelta());
        Long cacheRead = negate(original.cacheReadTokensDelta());
        Long cacheCreation = negate(original.cacheCreationTokensDelta());
        assertNetStaysNonNegative(target, input, output, cacheRead, cacheCreation);

        return new UsageAdjustment(UUID.randomUUID(), tenantId, target.usageEventId(), AdjustmentType.USAGE, input,
                output, cacheRead, cacheCreation, null, null, reason, reasonCode, null, reversalOfId, null,
                Instant.now(), idempotencyKey);
    }

    /**
     * Refuses a correction that would drive a dimension below zero — "we used minus
     * 300 output tokens" is not a fact worth recording.
     */
    private static void assertNetStaysNonNegative(AdjustmentTarget target, Long input, Long output, Long cacheRead,
            Long cacheCreation) {
        assertDimension("输入", target.netInputTokens(), input);
        assertDimension("输出", target.netOutputTokens(), output);
        assertDimension("缓存读", target.netCacheReadTokens(), cacheRead);
        assertDimension("缓存写", target.netCacheCreationTokens(), cacheCreation);
    }

    private static void assertDimension(String label, Long currentNet, Long proposedDelta) {
        if (proposedDelta == null) {
            return;
        }
        long current = currentNet == null ? 0L : currentNet;
        if (current + proposedDelta < 0L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADJUSTMENT_WOULD_GO_NEGATIVE",
                    "调整后" + label + "token 为负（当前净额 " + current + "，本次 " + proposedDelta + "）。");
        }
    }

    private static String summary(UsageAdjustment a) {
        // Reason is operator-supplied free text, so it is JSON-escaped rather than
        // interpolated raw.
        return "{\"usageEventId\":\"" + a.usageEventId() + "\",\"reversalOfId\":"
                + (a.reversalOfId() == null ? "null" : "\"" + a.reversalOfId() + "\"") + ",\"inputDelta\":"
                + a.inputTokensDelta() + ",\"outputDelta\":" + a.outputTokensDelta() + ",\"cacheReadDelta\":"
                + a.cacheReadTokensDelta() + ",\"cacheCreationDelta\":" + a.cacheCreationTokensDelta()
                + ",\"reasonCode\":" + (a.reasonCode() == null ? "null" : "\"" + jsonEscape(a.reasonCode()) + "\"")
                + ",\"reason\":\"" + jsonEscape(a.reason()) + "\"}";
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static UsageAdjustmentView view(UsageAdjustment a) {
        return new UsageAdjustmentView(a.id(), a.usageEventId(), a.adjustmentType().name(), a.inputTokensDelta(),
                a.outputTokensDelta(), a.cacheReadTokensDelta(), a.cacheCreationTokensDelta(), a.amountDelta(),
                a.currencyCode(), a.reason(), a.reasonCode(), a.reconciliationRowId(), a.reversalOfId(), a.createdBy(),
                a.createdAt());
    }

    private static String requireText(String value, String code, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean anyNonZeroDelta(Long... deltas) {
        for (Long delta : deltas) {
            if (delta != null && delta != 0L) {
                return true;
            }
        }
        return false;
    }

    private static Long negate(Long value) {
        return value == null ? null : -value;
    }
}
