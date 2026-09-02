package com.miqroera.miqrokey.controlplane.dto;

import java.util.List;

/**
 * Keyset-paged approval queue: {@code nextCursor} is opaque and passed back as
 * {@code before} to fetch the next page; null means no more items.
 */
public record ModelApprovalPage(List<ModelApprovalView> items, String nextCursor) {
}
