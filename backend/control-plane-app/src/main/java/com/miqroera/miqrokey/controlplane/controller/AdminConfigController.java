package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminConfigService;
import com.miqroera.miqrokey.domain.model.ConfigEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Global configuration center (P3.3, api-contract §5.15): grouped key-value
 * entries. SYSTEM_ADMIN-only via RoleInterceptor. Non-secret configuration
 * only.
 */
@RestController
@RequestMapping("/api/v1/admin/configs")
public class AdminConfigController {

    private final AdminConfigService configService;
    private final UserContext userContext;

    public AdminConfigController(AdminConfigService configService, UserContext userContext) {
        this.configService = configService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ConfigEntry> list(@RequestParam(required = false) String group) {
        return configService.list(userContext.getUser().tenantId(), group);
    }

    /** Creates or updates the (group, key) entry in place. */
    @PutMapping
    public ConfigEntry put(@Valid @RequestBody PutRequest body) {
        var user = userContext.getUser();
        return configService.put(user.tenantId(), user.id(), body.group(), body.key(), body.value(),
                body.description());
    }

    @DeleteMapping("/{group}/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String group, @PathVariable String key) {
        configService.delete(userContext.getUser().tenantId(), group, key);
    }

    public record PutRequest(@NotBlank @Size(max = 64) String group, @NotBlank @Size(max = 128) String key,
            @NotBlank String value, @Size(max = 500) String description) {
    }
}
