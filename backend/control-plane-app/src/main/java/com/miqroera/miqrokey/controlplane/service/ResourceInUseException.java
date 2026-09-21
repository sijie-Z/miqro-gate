package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ResourceDependency;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Delete precondition failure (I21): the target is still referenced inside the
 * tenant. Carries the dependency list for the 409 problem body —
 * {@code code=RESOURCE_IN_USE} + {@code dependencies:[{type,id,name,detail}]} —
 * so the admin can see what blocks the delete and release it first instead of
 * facing a bare foreign-key failure. Future delete surfaces adopt the same
 * exception; webhook endpoints are the first (alert-rule references).
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
