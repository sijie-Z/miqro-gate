package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.BudgetView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminBudgetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Monthly per-project budgets (G8.2, api-contract §5.11): plan + live spend
 * watermark, alerting only — never blocking. SYSTEM_ADMIN-only via
 * RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBudgetController {

    private final AdminBudgetService budgetService;
    private final UserContext userContext;

    public AdminBudgetController(AdminBudgetService budgetService, UserContext userContext) {
        this.budgetService = budgetService;
        this.userContext = userContext;
    }

    /** All project budgets for one month with live spend watermarks. */
    @GetMapping("/budgets")
    public List<BudgetView> monthly(@RequestParam(required = false) String month) {
        return budgetService.monthlyView(userContext.getUser().tenantId(), month != null ? month : currentMonth());
    }

    @GetMapping("/projects/{projectId}/budget")
    public BudgetView view(@PathVariable UUID projectId, @RequestParam(required = false) String month) {
        return budgetService.view(userContext.getUser().tenantId(), projectId, month != null ? month : currentMonth());
    }

    /** Creates or updates the (project, month) budget in place. */
    @PutMapping("/projects/{projectId}/budget")
    public BudgetView put(@PathVariable UUID projectId, @Valid @RequestBody PutBudgetRequest body) {
        return budgetService.put(userContext.getUser().tenantId(), projectId, body.month(), body.amount(),
                body.currency(), body.alertThresholdPct());
    }

    @DeleteMapping("/projects/{projectId}/budget")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @RequestParam(required = false) String month) {
        budgetService.delete(userContext.getUser().tenantId(), projectId, month != null ? month : currentMonth());
    }

    private static String currentMonth() {
        return YearMonth.now().toString();
    }

    public record PutBudgetRequest(
            @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "month must be YYYY-MM") String month,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
            @Pattern(regexp = "[A-Za-z]{3}") String currency,
            @DecimalMin(value = "0.01") @DecimalMax(value = "100.00") BigDecimal alertThresholdPct) {
    }
}
