package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.AuthenticationException;
import com.miqroera.miqrokey.controlplane.security.ResourceOwnershipException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates exceptions into RFC 9457 Problem Details responses.
 *
 * <p>
 * Every response uses {@code Content-Type: application/problem+json} with a
 * stable {@code code} token and a unique {@code requestId} for correlation.
 * Never exposes stack traces, secret material, or internal paths.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthenticationException e, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(401, "UNAUTHORIZED", "Authentication failed", e.getMessage(),
                requestId);
        // Never log authentication failure details — they are user-facing generic
        // messages.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ResourceOwnershipException.class)
    public ResponseEntity<Map<String, Object>> handleOwnership(ResourceOwnershipException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(404, "NOT_FOUND", "Resource not found", e.getMessage(), requestId);
        // Generic message prevents resource enumeration — never log details.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        List<Map<String, String>> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "code", "INVALID")).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Validation failed");
        body.put("status", 400);
        body.put("code", "VALIDATION_FAILED");
        body.put("detail", "One or more fields are invalid.");
        body.put("requestId", requestId);
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        LOG.error("Unhandled exception [requestId={}]", requestId, e);
        Map<String, Object> body = problemDetail(500, "INTERNAL_ERROR", "Internal server error",
                "An unexpected error occurred.", requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        if (header != null && !header.isBlank())
            return header;
        return UUID.randomUUID().toString();
    }

    private static Map<String, Object> problemDetail(int status, String code, String title, String detail,
            String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        body.put("detail", detail);
        body.put("requestId", requestId);
        return body;
    }
}
