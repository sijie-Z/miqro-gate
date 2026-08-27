package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.OwnershipService;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.domain.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Test-only ownership/IDOR endpoint for authorization integration testing.
 *
 * <p>
 * This controller lives outside {@code /api/v1/admin/**} so the
 * {@code RoleInterceptor} does not block non-SYSTEM_ADMIN users before
 * {@link OwnershipService#assertSelfOrAdmin} runs. It is protected by normal
 * session authentication via {@code SessionFilter}.
 * </p>
 *
 * <p>
 * Only available in the test classpath; not compiled into the production
 * artifact.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/test")
public class OwnershipTestController {

    private final UserContext userContext;
    private final OwnershipService ownershipService;

    public OwnershipTestController(UserContext userContext, OwnershipService ownershipService) {
        this.userContext = userContext;
        this.ownershipService = ownershipService;
    }

    /**
     * Test-only endpoint proving cross-user IDOR is denied for USER role while
     * self-access and admin override succeed. The path parameter is treated as the
     * resource owner's user ID so the OwnershipService can assert ownership.
     *
     * <p>
     * Self access → 200. Cross-user access → 404 (resource hiding). SYSTEM_ADMIN →
     * 200 (admin override). Unauthenticated → 401.
     * </p>
     */
    @GetMapping("/ownership/{ownerUserId}")
    public ResponseEntity<Map<String, Object>> ownershipProtected(@PathVariable UUID ownerUserId) {
        User currentUser = userContext.getUser();
        ownershipService.assertSelfOrAdmin(ownerUserId, currentUser);

        return ResponseEntity.ok(Map.of("message", "access granted", "currentUserId", currentUser.id().toString(),
                "resourceOwnerId", ownerUserId.toString()));
    }
}
