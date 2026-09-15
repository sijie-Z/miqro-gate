package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.PriceSnapshotView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminPriceService;
import com.miqroera.miqrokey.controlplane.service.AdminPriceSyncService;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin unit-price catalog (api-contract §5.9): latest price per (product,
 * model, token type) and append-only price snapshots. SYSTEM_ADMIN-only
 * (RoleInterceptor deny-by-default).
 */
@RestController
@RequestMapping("/api/v1/admin/prices")
public class AdminPriceController {

    private final AdminPriceService priceService;
    private final AdminPriceSyncService priceSyncService;
    private final UserContext userContext;

    public AdminPriceController(AdminPriceService priceService, AdminPriceSyncService priceSyncService,
            UserContext userContext) {
        this.priceService = priceService;
        this.priceSyncService = priceSyncService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<PriceSnapshotView> list() {
        return priceService.listLatest();
    }

    @PostMapping
    public ResponseEntity<PriceSnapshotView> create(@Valid @RequestBody CreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(priceService.create(userContext.getUser().tenantId(), body.providerProductId(), body.modelId(),
                        body.tokenType(), body.currency(), body.unitPrice(), body.source(),
                        userContext.getUser().id()));
    }

    /**
     * Pulls the current public price index and upserts snapshots for the catalog
     * models of every mapped PAYG product (issue #585). A fetch failure writes
     * nothing and answers 502 {@code PRICE_SYNC_FAILED}.
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return priceSyncService.sync(user.tenantId(), user.id(), AuditContext.human(user.id(), requestId(httpReq)));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    public record CreateRequest(@NotNull UUID providerProductId, @NotBlank @Size(max = 200) String modelId,
            @NotBlank String tokenType, @NotBlank @Size(max = 8) String currency,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal unitPrice,
            @NotBlank @Size(max = 32) String source) {
    }
}
