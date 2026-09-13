package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.CryptoReencryptReport;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.CryptoReencryptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Master-key migration operations (issue #432): the batch re-encryption step of
 * the documented key-rotation runbook (configuration-reference §4.3,
 * security.md「后台分批重新加密旧密文」). SYSTEM_ADMIN-only via the deny-by-default
 * {@code /api/v1/admin/**} interceptor; CSRF enforced; every invocation is
 * audited as {@code CRYPTO_REENCRYPT}.
 */
@RestController
@RequestMapping("/api/v1/admin/crypto")
public class AdminCryptoController {

    private final CryptoReencryptionService reencryptionService;
    private final UserContext userContext;

    public AdminCryptoController(CryptoReencryptionService reencryptionService, UserContext userContext) {
        this.reencryptionService = reencryptionService;
        this.userContext = userContext;
    }

    /**
     * Migrates this tenant's stored ciphertexts onto the active AES key version.
     * Idempotent and safe to re-run; {@code remaining == 0} in the report is the
     * precondition for retiring the old key version from the configuration.
     */
    @PostMapping("/reencrypt")
    public CryptoReencryptReport reencrypt(HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return reencryptionService.reencrypt(user.tenantId(), user.id(), requestId(httpReq));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
