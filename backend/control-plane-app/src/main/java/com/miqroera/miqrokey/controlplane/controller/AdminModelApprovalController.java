package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ModelApprovalPage;
import com.miqroera.miqrokey.controlplane.dto.ModelApprovalView;
import com.miqroera.miqrokey.controlplane.dto.ReviewModelApprovalRequest;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import com.miqroera.miqrokey.controlplane.service.ModelApprovalService;
import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;
import com.miqroera.miqrokey.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Admin model-approval queue (原始设计文档 §5.6): page through requests by status and
 * approve/reject them. SYSTEM_ADMIN-only via the deny-by-default interceptor on
 * {@code /api/v1/admin/**}.
 *
 * <p>
 * Pagination is a keyset cursor:
 * {@code GET ?status=PENDING&size=20&before=<cursor>} returns newest-first
 * items plus a {@code nextCursor} for the next page. The cursor encodes the
 * last item's {@code (created_at, id)} — opaque to clients.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/model-approvals")
public class AdminModelApprovalController {

    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    private final ModelApprovalService modelApprovalService;
    private final UserContext userContext;

    public AdminModelApprovalController(ModelApprovalService modelApprovalService, UserContext userContext) {
        this.modelApprovalService = modelApprovalService;
        this.userContext = userContext;
    }

    @GetMapping
    public ModelApprovalPage list(@RequestParam(required = false) ModelApprovalStatus status,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size, @RequestParam(required = false) String before) {
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "size must be between 1 and " + MAX_SIZE);
        }
        Cursor cursor = before == null || before.isBlank() ? Cursor.start() : Cursor.decode(before);
        // Fetch one extra row to learn whether another page exists.
        List<ModelApprovalView> items = modelApprovalService.listQueue(user(), status, size + 1, cursor.createdAt,
                cursor.id);
        String nextCursor = items.size() > size
                ? Cursor.encode(items.get(size - 1).createdAt(), items.get(size - 1).id())
                : null;
        return new ModelApprovalPage(items.size() > size ? items.subList(0, size) : items, nextCursor);
    }

    @PostMapping("/{id}/approve")
    public ModelApprovalView approve(@PathVariable UUID id,
            @Valid @RequestBody(required = false) ReviewModelApprovalRequest request, HttpServletRequest httpReq) {
        return modelApprovalService.approve(user(), id, request == null ? null : request.reviewNote(),
                requestId(httpReq));
    }

    @PostMapping("/{id}/reject")
    public ModelApprovalView reject(@PathVariable UUID id,
            @Valid @RequestBody(required = false) ReviewModelApprovalRequest request, HttpServletRequest httpReq) {
        return modelApprovalService.reject(user(), id, request == null ? null : request.reviewNote(),
                requestId(httpReq));
    }

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    /** Opaque keyset cursor: base64("{createdAtEpochMillis}:{id}"). */
    private record Cursor(Instant createdAt, UUID id) {

        static Cursor start() {
            return new Cursor(null, null);
        }

        static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
                int sep = raw.indexOf(':');
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, sep))),
                        UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "Invalid pagination cursor");
            }
        }

        static String encode(Instant createdAt, UUID id) {
            String raw = createdAt.toEpochMilli() + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
