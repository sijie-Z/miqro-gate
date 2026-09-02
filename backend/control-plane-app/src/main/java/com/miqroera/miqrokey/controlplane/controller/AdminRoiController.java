package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.RoiReportView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminRoiService;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Cache-ROI report (api-contract §5.20, 原始设计文档 P5.4): window + per-day cache
 * savings over upstream spend, SYSTEM_ADMIN-only. The shared usage summary
 * validation applies (window bounded by the 93-day rule; malformed ISO instants
 * → 400 PARAM_INVALID).
 */
@RestController
@RequestMapping("/api/v1/admin/usage/roi")
public class AdminRoiController {

    private final AdminRoiService roiService;
    private final UserContext userContext;

    public AdminRoiController(AdminRoiService roiService, UserContext userContext) {
        this.roiService = roiService;
        this.userContext = userContext;
    }

    @GetMapping
    public RoiReportView report(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromInstant = from == null || from.isBlank() ? AdminRoiService.defaultFrom() : parseInstant(from);
        Instant toInstant = to == null || to.isBlank() ? Instant.now() : parseInstant(to);
        return roiService.report(userContext.getUser().tenantId(), fromInstant, toInstant);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "from/to must be ISO-8601 instants");
        }
    }
}
