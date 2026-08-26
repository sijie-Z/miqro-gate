package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.service.AdminProviderService;
import com.miqroera.miqrokey.controlplane.service.AdminProviderService.ProductView;
import com.miqroera.miqrokey.domain.model.Provider;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin provider products (G5.3, api-contract §5): product instances with
 * protocol, Plan shape, implementation status and balance authority, plus the
 * provider list. SYSTEM_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/provider-products")
public class AdminProviderProductController {

    private final AdminProviderService providerService;

    public AdminProviderProductController(AdminProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public List<ProductView> list() {
        return providerService.listProducts();
    }

    @GetMapping("/{productId}")
    public ProviderProduct get(@PathVariable UUID productId) {
        return providerService.product(productId);
    }

    @GetMapping("/providers")
    public List<Provider> providers() {
        return providerService.listProviders();
    }
}
