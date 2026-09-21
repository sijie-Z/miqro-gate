package com.miqroera.miqrokey.controlplane.dto;

import java.util.UUID;

/**
 * One referencing resource blocking a delete (I21, Tencent model-API delete
 * semantics, raw 1826/134816): the dependent's type/id plus a human-readable
 * name/detail so the admin can navigate to it and release the reference first.
 * Serialized inside the 409 {@code RESOURCE_IN_USE} problem body
 * ({@code dependencies} array).
 */
public record ResourceDependency(String type, UUID id, String name, String detail) {
}
