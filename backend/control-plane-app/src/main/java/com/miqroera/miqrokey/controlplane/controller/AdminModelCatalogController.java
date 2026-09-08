package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ModelCatalogView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ModelCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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
    private final UserContext userContext;

    public AdminModelCatalogController(ModelCatalogService catalogService, UserContext userContext) {
        this.catalogService = catalogService;
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
    public ModelCatalogView add(@Valid @RequestBody AddRequest body) {
        var user = userContext.getUser();
        return catalogService.addManual(user.id(), body.providerProductId(), body.modelId(), body.displayName(),
                body.contextWindow(), body.maxOutputTokens());
    }

    /** Removes a MANUAL row only. */
    @DeleteMapping("/{rowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID rowId) {
        catalogService.removeManual(userContext.getUser().id(), rowId);
    }

    public record AddRequest(@NotNull UUID providerProductId, @NotBlank @Size(max = 128) String modelId,
            @Size(max = 200) String displayName, @Positive Integer contextWindow, @Positive Integer maxOutputTokens) {
    }
}
