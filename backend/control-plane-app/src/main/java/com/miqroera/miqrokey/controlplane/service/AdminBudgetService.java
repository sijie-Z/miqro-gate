package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.BudgetView;
import com.miqroera.miqrokey.domain.model.Budget;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.repository.BudgetRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Monthly per-project budgets (G8.2, {@code budget} V7): the alerting-only
 * quota plan — a budget never blocks traffic. The spend watermark is computed
 * at read time from the per-project cost allocation of the budget month, and
 * the alert level (NORMAL / WARNING / EXCEEDED) derives from the configured
 * threshold percentage, mirroring the Tencent consumer-quota alert states.
 */
@Service
public class AdminBudgetService {

    private final BudgetRepository budgetRepository;
    private final ProjectRepository projectRepository;
    private final AdminUsageStatsService usageStatsService;

    public AdminBudgetService(BudgetRepository budgetRepository, ProjectRepository projectRepository,
            AdminUsageStatsService usageStatsService) {
        this.budgetRepository = budgetRepository;
        this.projectRepository = projectRepository;
        this.usageStatsService = usageStatsService;
    }

    public List<BudgetView> monthlyView(UUID tenantId, String month) {
        validateMonth(month);
        return budgetRepository.findAllByTenantAndMonth(tenantId, month).stream().map(b -> toView(tenantId, b))
                .toList();
    }

    public BudgetView view(UUID tenantId, UUID projectId, String month) {
        validateMonth(month);
        Budget budget = budgetRepository.findByProjectAndMonth(tenantId, projectId, month)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BUDGET_NOT_FOUND", "该月份未设置预算。"));
        return toView(tenantId, budget);
    }

    /** Creates or updates the (project, month) budget in place (upsert). */
    @Transactional
    public BudgetView put(UUID tenantId, UUID projectId, String month, BigDecimal amount, String currency,
            BigDecimal alertThresholdPct) {
        validateMonth(month);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在。"));
        if (!project.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在。");
        }
        Budget budget = new Budget(UUID.randomUUID(), tenantId, projectId, month, amount,
                currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase(),
                alertThresholdPct != null ? alertThresholdPct : new BigDecimal("80"), "ACTIVE", 0, Instant.now(),
                Instant.now());
        return toView(tenantId, budgetRepository.upsert(budget));
    }

    @Transactional
    public void delete(UUID tenantId, UUID projectId, String month) {
        validateMonth(month);
        if (!budgetRepository.delete(tenantId, projectId, month)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BUDGET_NOT_FOUND", "该月份未设置预算。");
        }
    }

    static void validateMonth(String month) {
        if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MONTH_INVALID", "月份必须是 YYYY-MM 格式。");
        }
    }

    private BudgetView toView(UUID tenantId, Budget budget) {
        Project project = projectRepository.findById(budget.projectId()).orElse(null);
        BigDecimal spent = spend(tenantId, budget);
        BigDecimal spentPct = budget.amount().signum() > 0
                ? spent.multiply(BigDecimal.valueOf(100)).divide(budget.amount(), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        String level = spentPct.compareTo(BigDecimal.valueOf(100)) >= 0
                ? "EXCEEDED"
                : spentPct.compareTo(budget.alertThresholdPct()) >= 0 ? "WARNING" : "NORMAL";
        return new BudgetView(budget.projectId(), project != null ? project.code() : null,
                project != null ? project.name() : null, budget.periodMonth(), budget.amount(), budget.currency(),
                budget.alertThresholdPct(), budget.status(), spent, spentPct, level);
    }

    /**
     * Allocated cost of the budget month for the project (usage + price snapshots).
     */
    private BigDecimal spend(UUID tenantId, Budget budget) {
        YearMonth month = YearMonth.parse(budget.periodMonth());
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        UsageSummary summary = usageStatsService.summary(tenantId, "project", from, to, null, budget.projectId(), null,
                null, null, null, null);
        return summary.totals().cost().projectAllocated();
    }
}
