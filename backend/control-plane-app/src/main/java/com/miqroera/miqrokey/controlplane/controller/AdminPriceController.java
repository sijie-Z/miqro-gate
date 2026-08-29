package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.PriceSnapshotView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminPriceService;
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
    private final UserContext userContext;

    public AdminPriceController(AdminPriceService priceService, UserContext userContext) {
        this.priceService = priceService;
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

    public record CreateRequest(@NotNull UUID providerProductId, @NotBlank @Size(max = 200) String modelId,
            @NotBlank String tokenType, @NotBlank @Size(max = 8) String currency,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal unitPrice,
            @NotBlank @Size(max = 32) String source) {
    }
}
