package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ModelCatalogView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.ModelCatalogProbeService;
import com.miqroera.miqrokey.controlplane.service.ModelCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin model-catalog maintenance (F18, Tencent raw 5 fallback): the official
 * fetch pipeline only writes on success, so a provider probe failure used to
 * leave the operator without any entry path. These endpoints let an admin
 * register a model id manually ({@code source=MANUAL}); manual rows survive
 * official refreshes and only manual rows can be removed by hand.
 * SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class AdminModelCatalogController {

    private final ModelCatalogService catalogService;
    private final ModelCatalogProbeService probeService;
    private final UserContext userContext;

    public AdminModelCatalogController(ModelCatalogService catalogService, ModelCatalogProbeService probeService,
            UserContext userContext) {
        this.catalogService = catalogService;
        this.probeService = probeService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ModelCatalogView> list(@RequestParam(required = false) UUID providerProductId,
            @RequestParam(required = false) String source) {
        return catalogService.list(providerProductId, source);
    }

    /** Manual model entry (probe-failure fallback). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelCatalogView add(@Valid @RequestBody AddRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return catalogService.addManual(user.tenantId(), user.id(), body.providerProductId(), body.modelId(),
                body.displayName(), body.contextWindow(), body.maxOutputTokens(),
                AuditContext.human(user.id(), requestId(httpReq)));
    }

    /** Admin-triggered model probe (#346, I4, raw doc 05). */
    @PostMapping("/probe")
    public Map<String, Object> probe(@Valid @RequestBody ProbeRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return probeService.probe(user.tenantId(), user.id(), body.providerProductId(),
                AuditContext.human(user.id(), requestId(httpReq)));
    }

    /**
     * Last probe outcome for a product (visible failure surface; nulls when never
     * probed).
     */
    @GetMapping("/probe-status")
    public Map<String, Object> probeStatus(@RequestParam UUID providerProductId) {
        return probeService.probeStatus(providerProductId);
    }

    /** Removes a MANUAL row only. */
    @DeleteMapping("/{rowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID rowId, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        catalogService.removeManual(user.tenantId(), user.id(), rowId,
                AuditContext.human(user.id(), requestId(httpReq)));
    }

    public record AddRequest(@NotNull UUID providerProductId, @NotBlank @Size(max = 128) String modelId,
            @Size(max = 200) String displayName, @Positive Integer contextWindow, @Positive Integer maxOutputTokens) {
    }

    public record ProbeRequest(@NotNull UUID providerProductId) {
    }
    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

}
