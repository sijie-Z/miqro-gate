package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.miqroera.miqrokey.controlplane.security.AuthenticationException;
import com.miqroera.miqrokey.controlplane.security.ResourceOwnershipException;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import com.miqroera.miqrokey.controlplane.service.ResourceInUseException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        int status = e.getStatus().value();
        Map<String, Object> body = problemDetail(status, e.getCode(), e.getCode().replace('_', ' ').toLowerCase(),
                e.getMessage(), requestId);
        return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(ResourceInUseException.class)
    public ResponseEntity<Map<String, Object>> handleResourceInUse(ResourceInUseException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(409, e.getCode(), "resource in use", e.getMessage(), requestId);
        body.put("dependencies", e.getDependencies());
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
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

    /**
     * Unconvertible query parameters (e.g. {@code userId=not-a-uuid}) become
     * {@code 400 PARAM_INVALID} (G4.1 contract: invalid filter values are rejected,
     * never treated as internal errors).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Invalid parameter");
        body.put("status", 400);
        body.put("code", "PARAM_INVALID");
        body.put("detail", "Parameter '" + e.getName() + "' has an invalid value.");
        body.put("requestId", requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /**
     * Malformed request JSON (unknown enum value, wrong type, truncated body) is a
     * client error, never a 500: 400 PARAM_INVALID with the field name when the
     * parser reports one.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Invalid request body");
        body.put("status", 400);
        body.put("code", "PARAM_INVALID");
        String field = fieldOf(e);
        body.put("detail",
                field == null
                        ? "The request body is not valid JSON for this endpoint."
                        : "Field '" + field + "' has an invalid value.");
        body.put("requestId", requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String fieldOf(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof InvalidFormatException ife && ife.getPath() != null && !ife.getPath().isEmpty()) {
            return ife.getPath().get(ife.getPath().size() - 1).getFieldName();
        }
        return null;
    }

    /**
     * Database constraint conflicts no service mapped locally (#412): a duplicate
     * key, a foreign-key/CHECK violation or a NOT NULL breach is a conflict between
     * the request and existing state — 409, never a bare 500. Full trace
     * server-side at WARN; the response never carries SQL.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        LOG.warn("Data integrity conflict [requestId={}]", requestId, e);
        Map<String, Object> body = problemDetail(409, "RESOURCE_CONFLICT", "Resource conflict",
                "请求与现有数据约束冲突（重复或引用不允许），请刷新后重试。", requestId);
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /**
     * Deadlocks and lock-acquisition failures are transient concurrency conflicts
     * (#404/#412): the same retry-shaped 409 as the services' local mappings, never
     * a bare 500.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrency(ConcurrencyFailureException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        LOG.warn("Concurrency conflict [requestId={}]", requestId, e);
        Map<String, Object> body = problemDetail(409, "CONCURRENT_MODIFICATION", "Concurrent modification",
                "并发操作冲突，请刷新后重试。", requestId);
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /**
     * Framework-level client errors would otherwise be swallowed by the
     * {@code Exception} catch-all into 500s (#412): a missing required query
     * parameter is 400, a wrong method 405, an unsupported media type 415 and an
     * unknown path 404.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(400, "PARAM_INVALID", "Invalid parameter",
                "缺少必填参数 '" + e.getParameterName() + "'。", requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(405, "METHOD_NOT_ALLOWED", "Method not allowed",
                "该路径不支持 " + e.getMethod() + " 方法。", requestId);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type",
                "请求 Content-Type 不受支持。", requestId);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        Map<String, Object> body = problemDetail(404, "NOT_FOUND", "Not found", "请求的路径不存在。", requestId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
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
