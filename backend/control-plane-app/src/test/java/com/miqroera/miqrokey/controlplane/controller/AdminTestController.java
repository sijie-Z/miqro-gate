package com.miqroera.miqrokey.controlplane.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test-only admin endpoints for authorization integration testing. This
 * controller is only available in the test classpath; not compiled into the
 * production artifact.
 *
 * <p>
 * Ownership/IDOR tests are in {@link OwnershipTestController} which lives
 * outside {@code /api/v1/admin/**} so USER self-access can reach
 * {@code OwnershipService} without being blocked by {@code RoleInterceptor}.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTestController {

    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> adminTest() {
        return ResponseEntity.ok(Map.of("message", "admin access granted"));
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, String>> adminUserDetail(@PathVariable String userId) {
        return ResponseEntity.ok(Map.of("userId", userId, "message", "user detail"));
    }
}
