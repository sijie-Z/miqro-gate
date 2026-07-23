package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.UserRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a controller method requires a specific {@link UserRole}.
 * Checked by {@link RoleInterceptor}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    UserRole value();
}
