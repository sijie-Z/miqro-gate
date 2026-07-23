package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Minimal reusable ownership assertion for G1.3.
 *
 * <p>
 * The {@link #assertSelfOrAdmin} method is the canonical enforcement point for
 * resource ownership checks. It throws a {@code ResourceOwnershipException}
 * with a generic "not found" message when the current user is neither the
 * resource owner nor a {@code SYSTEM_ADMIN}. Using a generic message prevents
 * resource enumeration via IDOR.
 * </p>
 *
 * <p>
 * {@code ResourceOwnershipException} maps to HTTP 404 (resource hiding) rather
 * than 401/403, which would leak the existence of the resource.
 * </p>
 *
 * <p>
 * G1.4 will extend this with richer resource-type context when business
 * endpoints arrive.
 * </p>
 */
@Service
public class OwnershipService {

    /**
     * Assert that the current user is either the resource owner or a SYSTEM_ADMIN.
     * Throws with a generic "not found" message to prevent enumeration.
     *
     * @param resourceOwnerId
     *            the user ID that owns the resource
     * @param currentUser
     *            the authenticated user from the session
     * @throws ResourceOwnershipException
     *             if neither condition is met
     */
    public void assertSelfOrAdmin(UUID resourceOwnerId, User currentUser) {
        if (currentUser == null) {
            throw new ResourceOwnershipException("Resource not found.");
        }
        if (currentUser.role() == UserRole.SYSTEM_ADMIN) {
            return;
        }
        if (currentUser.id().equals(resourceOwnerId)) {
            return;
        }
        throw new ResourceOwnershipException("Resource not found.");
    }
}
