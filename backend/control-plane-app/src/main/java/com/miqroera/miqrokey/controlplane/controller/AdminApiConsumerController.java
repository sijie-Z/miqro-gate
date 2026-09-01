package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ApiConsumerView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ApiConsumerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API-consumer management (ADR-0010): create (one-time key), list,
 * disable. SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/api-consumers")
public class AdminApiConsumerController {

    private final ApiConsumerService consumerService;
    private final UserContext userContext;

    public AdminApiConsumerController(ApiConsumerService consumerService, UserContext userContext) {
        this.consumerService = consumerService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ApiConsumerView> list() {
        return consumerService.list(userContext.getUser().tenantId());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest body) {
        ApiConsumerService.CreatedConsumer created = consumerService.create(userContext.getUser().tenantId(),
                body.name().trim());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("consumer", created.consumer(), "apiKey", created.apiKey(), "shownOnce", true));
    }

    @PostMapping("/{consumerId}/disable")
    public ApiConsumerView disable(@PathVariable UUID consumerId) {
        return consumerService.disable(userContext.getUser().tenantId(), consumerId);
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name) {
    }
}
