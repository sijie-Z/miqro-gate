package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.MeGrantsResponse;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.VirtualKeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Options for the Virtual Key creation form (api-contract §4): the caller's
 * projects with routing tags, the active credential grants per project with
 * their granted models, and the available purposes.
 */
@RestController
@RequestMapping("/api/v1/me/grants")
public class MeGrantsController {

    private final VirtualKeyService virtualKeyService;
    private final UserContext userContext;

    public MeGrantsController(VirtualKeyService virtualKeyService, UserContext userContext) {
        this.virtualKeyService = virtualKeyService;
        this.userContext = userContext;
    }

    @GetMapping
    public MeGrantsResponse grants() {
        return virtualKeyService.grantOptions(userContext.getUser());
    }
}
