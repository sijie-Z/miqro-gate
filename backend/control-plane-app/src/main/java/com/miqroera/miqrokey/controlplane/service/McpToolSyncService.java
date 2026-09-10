package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.McpToolsListClient.UpstreamTool;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MCP tool-list synchronization (issue #344, raw doc 03): fetches the upstream
 * {@code tools/list} and merges it into {@code mcp_tools}. New tools are
 * registered (with their baseline revision); changed descriptions publish the
 * next revision through F16 ({@code McpToolRevisionService}) and mirror back
 * onto the tool row. Tools the upstream no longer returns are only reported as
 * {@code absentUpstream} — the sync never disables or deletes locally reviewed
 * state. The write phase is delegated to {@link AdminMcpToolService#applySync}
 * so it runs in one transaction (the upstream call always happens outside it),
 * and {@code dryRun} computes the same report without writing or auditing
 * anything.
 */
@Service
public class McpToolSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolSyncService.class);

    /**
     * Placeholder HTTP mapping for MCP-native tools; they have no upstream HTTP
     * mapping.
     */
    static final String SYNCED_METHOD = "POST";
    static final String SYNCED_PATH = "/";
    static final int MAX_NAME_CHARS = 128;

    private final McpToolsListClient client;
    private final McpServiceRepository serviceRepository;
    private final McpToolRepository toolRepository;
    private final AdminMcpToolService toolService;
    private final KeyEncryptionProvider keyEncryptionProvider;

    public McpToolSyncService(McpToolsListClient client, McpServiceRepository serviceRepository,
            McpToolRepository toolRepository, AdminMcpToolService toolService,
            KeyEncryptionProvider keyEncryptionProvider) {
        this.client = client;
        this.serviceRepository = serviceRepository;
        this.toolRepository = toolRepository;
        this.toolService = toolService;
        this.keyEncryptionProvider = keyEncryptionProvider;
    }

    /**
     * Preview ({@code dryRun}) or apply the upstream tool list; returns the
     * per-item report.
     */
    public Map<String, Object> sync(UUID tenantId, UUID adminId, UUID serviceId, boolean dryRun, String requestId) {
        McpService service = serviceRepository.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
        List<UpstreamTool> upstream = client.fetchTools(service.endpoint(), resolveBackendBearer(service));
        if (dryRun) {
            return report(true, upstream.size(),
                    classify(toolRepository.findAllByService(tenantId, serviceId), upstream));
        }
        return toolService.applySync(tenantId, adminId, serviceId, upstream, requestId);
    }

    /** Diff classification shared by the preview and the transactional apply. */
    static Diff classify(List<McpTool> existing, List<UpstreamTool> upstream) {
        Map<String, McpTool> byName = new LinkedHashMap<>();
        existing.forEach(tool -> byName.put(tool.toolName(), tool));
        Set<String> seen = new LinkedHashSet<>();
        List<UpstreamTool> added = new ArrayList<>();
        List<McpTool> updated = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        int unchanged = 0;
        for (UpstreamTool tool : upstream) {
            if (tool.name().isEmpty()) {
                skipped.add(skip("", "上游条目缺少 name"));
                continue;
            }
            if (!seen.add(tool.name())) {
                skipped.add(skip(tool.name(), "上游重复返回同名工具"));
                continue;
            }
            McpTool local = byName.get(tool.name());
            if (local == null) {
                if (tool.name().length() > MAX_NAME_CHARS || !tool.name().matches("[a-z][a-z0-9_]*")) {
                    skipped.add(skip(tool.name(), "工具名必须为小写字母开头的 snake_case（≤128 字符）"));
                    continue;
                }
                added.add(tool);
            } else if (Objects.equals(trimToNull(local.description()), tool.description())) {
                unchanged++;
            } else {
                updated.add(local);
            }
        }
        List<String> absent = new ArrayList<>();
        for (McpTool tool : existing) {
            if (!seen.contains(tool.toolName())) {
                absent.add(tool.toolName());
            }
        }
        return new Diff(added, updated, unchanged, absent, skipped);
    }

    static Map<String, Object> report(boolean dryRun, int upstreamToolCount, Diff diff) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dryRun", dryRun);
        report.put("upstreamToolCount", upstreamToolCount);
        report.put("added", diff.addedSpecs().stream().map(UpstreamTool::name).toList());
        report.put("updated", diff.updatedTools().stream().map(McpTool::toolName).toList());
        report.put("unchanged", diff.unchanged());
        report.put("absentUpstream", diff.absentUpstream());
        report.put("skipped", diff.skipped());
        return report;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, String> skip(String toolName, String reason) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("toolName", toolName);
        entry.put("reason", reason);
        return entry;
    }

    /**
     * Bearer for API_KEY backends (V38): the ciphertext is read server-side only,
     * the plaintext is wiped after the header value is built, and any failure is
     * fail-closed with a message that never contains credential material.
     */
    private String resolveBackendBearer(McpService service) {
        if (!"API_KEY".equals(service.backendAuthMode())) {
            return null;
        }
        EncryptedSecret encrypted = serviceRepository.findBackendSecret(service.id(), service.tenantId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "TOOLS_SYNC_UPSTREAM_FAILED",
                        "后端凭证缺失，无法调用上游 tools/list。"));
        try {
            byte[] secret = keyEncryptionProvider.decrypt(encrypted, service.tenantId(), service.id());
            try {
                return "Bearer " + new String(secret, StandardCharsets.UTF_8);
            } finally {
                SecretWiping.clearArray(secret);
            }
        } catch (Exception e) {
            LOG.warn("MCP tools sync could not decrypt the backend secret for service {}", service.id());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOLS_SYNC_UPSTREAM_FAILED", "后端凭证不可用。");
        }
    }

    /**
     * Classified merge plan; {@code added} carries the upstream entries,
     * {@code updated} the local rows.
     */
    record Diff(List<UpstreamTool> addedSpecs, List<McpTool> updatedTools, int unchanged, List<String> absentUpstream,
            List<Map<String, String>> skipped) {
    }
}
