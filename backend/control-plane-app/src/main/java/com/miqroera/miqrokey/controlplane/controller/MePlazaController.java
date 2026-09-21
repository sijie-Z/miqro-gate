package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.MePlazaView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.MePlazaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service model plaza (#1201, 腾讯「AI 能力市场」对位): the caller's usable models
 * with unit prices, plus the catalog models still approvable on one of their
 * keys. Read-only and metadata-only; any authenticated user may call it for
 * their own keys.
 */
@RestController
@RequestMapping("/api/v1/me/plaza")
public class MePlazaController {

    private final MePlazaService plazaService;
    private final UserContext userContext;

    public MePlazaController(MePlazaService plazaService, UserContext userContext) {
        this.plazaService = plazaService;
        this.userContext = userContext;
    }

    @GetMapping("/models")
    public MePlazaView models() {
        return plazaService.view(userContext.getUser());
    }
}
