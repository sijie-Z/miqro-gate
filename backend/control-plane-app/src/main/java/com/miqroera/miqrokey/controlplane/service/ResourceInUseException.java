package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ResourceDependency;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Delete precondition failure (I21): the target is still referenced. Carries the
 * dependency list for the 409 problem body —
 * {@code code=RESOURCE_IN_USE} + {@code dependencies:[{type,id,name,detail}]} —
 * so the admin can see what blocks the delete and release it first instead of
 * facing a bare foreign-key failure. The blocking check is deliberately not
 * tenant-scoped (#1335: a foreign referrer has to block as well, otherwise the
 * delete detaches it silently through {@code ON DELETE SET NULL}); the reported
 * list is, so a 409 never names another tenant's resource — a caller whose every
 * referrer is foreign gets the count in the message and an empty list. Future
 * delete surfaces adopt the same exception; webhook endpoints are the first
 * (alert-rule references).
 */
public class ResourceInUseException extends ApiException {

    private final List<ResourceDependency> dependencies;

    public ResourceInUseException(String message, List<ResourceDependency> dependencies) {
        super(HttpStatus.CONFLICT, "RESOURCE_IN_USE", message);
        this.dependencies = List.copyOf(dependencies);
    }

    public List<ResourceDependency> getDependencies() {
        return dependencies;
    }
}
