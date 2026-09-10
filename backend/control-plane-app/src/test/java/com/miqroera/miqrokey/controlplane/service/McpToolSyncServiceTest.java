package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.McpToolSyncService.Diff;
import com.miqroera.miqrokey.controlplane.service.McpToolsListClient.UpstreamTool;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tool-list sync orchestration (#344): diff classification, the dry-run path
 * and the backend-credential fail-closed behavior. The write phase itself is
 * covered by the reconciliation-style integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolSyncService")
class McpToolSyncServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();

    @Mock
    private McpToolsListClient client;
    @Mock
    private McpServiceRepository serviceRepository;
    @Mock
    private McpToolRepository toolRepository;
    @Mock
    private AdminMcpToolService toolService;
    @Mock
    private KeyEncryptionProvider keyEncryptionProvider;

    private McpToolSyncService service() {
        return new McpToolSyncService(client, serviceRepository, toolRepository, toolService, keyEncryptionProvider);
    }

    // ------------------------------------------------------------- classify

    @Test
    @DisplayName("classify: added / updated / unchanged / absent / skipped with per-item reasons")
    void classifyMergesPerItem() {
        List<McpTool> existing = List.of(tool("keep", "same"), tool("stale", "old"), tool("gone", "local only"));
        List<UpstreamTool> upstream = List.of(new UpstreamTool("keep", "same"), new UpstreamTool("stale", "new"),
                new UpstreamTool("fresh", "hello"), new UpstreamTool("Bad-Name", "x"), new UpstreamTool("", "x"),
                new UpstreamTool("fresh", "dup"));

        Diff diff = McpToolSyncService.classify(existing, upstream);

        assertThat(diff.addedSpecs()).extracting(UpstreamTool::name).containsExactly("fresh");
        assertThat(diff.updatedTools()).extracting(McpTool::toolName).containsExactly("stale");
        assertThat(diff.unchanged()).isEqualTo(1);
        assertThat(diff.absentUpstream()).containsExactly("gone");
        assertThat(diff.skipped()).extracting(entry -> entry.get("toolName")).containsExactly("Bad-Name", "", "fresh");
    }

    @Test
    @DisplayName("classify: null and blank descriptions compare as equal")
    void classifyTreatsBlankAsNull() {
        Diff diff = McpToolSyncService.classify(List.of(tool("a", null), tool("b", "  ")),
                List.of(new UpstreamTool("a", null), new UpstreamTool("b", null)));

        assertThat(diff.addedSpecs()).isEmpty();
        assertThat(diff.updatedTools()).isEmpty();
        assertThat(diff.unchanged()).isEqualTo(2);
    }

    @Test
    @DisplayName("report mirrors the diff with the upstream count")
    void reportShape() {
        Diff diff = McpToolSyncService.classify(List.of(),
                List.of(new UpstreamTool("a", "d"), new UpstreamTool("a", "d")));

        Map<String, Object> report = McpToolSyncService.report(true, 2, diff);

        assertThat(report).containsEntry("dryRun", true).containsEntry("upstreamToolCount", 2)
                .containsEntry("added", List.of("a")).containsEntry("unchanged", 0)
                .containsEntry("absentUpstream", List.of());
        assertThat((List<?>) report.get("skipped")).hasSize(1);
    }

    // --------------------------------------------------------------- sync

    @Test
    @DisplayName("dryRun reports the diff and never touches the write path")
    void dryRunNeverWrites() {
        when(serviceRepository.findByIdAndTenantId(SERVICE_ID, TENANT)).thenReturn(Optional.of(service("VISITOR")));
        when(client.fetchTools("https://mcp.example/mcp", null)).thenReturn(List.of(new UpstreamTool("a", "d")));
        when(toolRepository.findAllByService(TENANT, SERVICE_ID)).thenReturn(List.of());

        Map<String, Object> report = service().sync(TENANT, ADMIN, SERVICE_ID, true, "rq-1");

        assertThat(report).containsEntry("dryRun", true).containsEntry("added", List.of("a"));
        verifyNoInteractions(toolService);
    }

    @Test
    @DisplayName("apply delegates to the transactional write phase")
    void applyDelegates() {
        when(serviceRepository.findByIdAndTenantId(SERVICE_ID, TENANT)).thenReturn(Optional.of(service("VISITOR")));
        List<UpstreamTool> upstream = List.of(new UpstreamTool("a", "d"));
        when(client.fetchTools("https://mcp.example/mcp", null)).thenReturn(upstream);
        when(toolService.applySync(TENANT, ADMIN, SERVICE_ID, upstream, "rq-2"))
                .thenReturn(Map.of("added", List.of("a")));

        assertThat(service().sync(TENANT, ADMIN, SERVICE_ID, false, "rq-2")).containsEntry("added", List.of("a"));
    }

    @Test
    @DisplayName("API_KEY backends attach the decrypted bearer; VISITOR attaches none")
    void backendBearerResolution() {
        EncryptedSecret encrypted = new EncryptedSecret("ct".getBytes(StandardCharsets.UTF_8),
                "nonce".getBytes(StandardCharsets.UTF_8), "v1");
        when(serviceRepository.findByIdAndTenantId(SERVICE_ID, TENANT)).thenReturn(Optional.of(service("API_KEY")));
        when(serviceRepository.findBackendSecret(SERVICE_ID, TENANT)).thenReturn(Optional.of(encrypted));
        when(keyEncryptionProvider.decrypt(eq(encrypted), eq(TENANT), eq(SERVICE_ID)))
                .thenReturn("sk-upstream".getBytes(StandardCharsets.UTF_8));
        when(client.fetchTools(eq("https://mcp.example/mcp"), anyString())).thenReturn(List.of());
        when(toolRepository.findAllByService(TENANT, SERVICE_ID)).thenReturn(List.of());

        service().sync(TENANT, ADMIN, SERVICE_ID, true, "rq-3");

        verify(client).fetchTools("https://mcp.example/mcp", "Bearer sk-upstream");
    }

    @Test
    @DisplayName("API_KEY without a stored secret fails closed before any upstream call")
    void missingSecretFailsClosed() {
        when(serviceRepository.findByIdAndTenantId(SERVICE_ID, TENANT)).thenReturn(Optional.of(service("API_KEY")));
        when(serviceRepository.findBackendSecret(SERVICE_ID, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().sync(TENANT, ADMIN, SERVICE_ID, true, "rq-4"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(502);
                    assertThat(e.getCode()).isEqualTo("TOOLS_SYNC_UPSTREAM_FAILED");
                });
        verify(client, never()).fetchTools(anyString(), any());
    }

    @Test
    @DisplayName("decrypt failure fails closed with a credential-free message")
    void decryptFailureFailsClosed() {
        EncryptedSecret encrypted = new EncryptedSecret("ct".getBytes(StandardCharsets.UTF_8),
                "nonce".getBytes(StandardCharsets.UTF_8), "v1");
        when(serviceRepository.findByIdAndTenantId(SERVICE_ID, TENANT)).thenReturn(Optional.of(service("API_KEY")));
        when(serviceRepository.findBackendSecret(SERVICE_ID, TENANT)).thenReturn(Optional.of(encrypted));
        when(keyEncryptionProvider.decrypt(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad key version"));

        assertThatThrownBy(() -> service().sync(TENANT, ADMIN, SERVICE_ID, true, "rq-5")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getMessage()).doesNotContain("bad key version"));
        verify(client, never()).fetchTools(anyString(), any());
    }

    @Test
    @DisplayName("unknown service is a 404 before any upstream call")
    void unknownServiceIs404() {
        when(serviceRepository.findByIdAndTenantId(SERVICE_ID, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().sync(TENANT, ADMIN, SERVICE_ID, true, "rq-6")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("MCP_SERVICE_NOT_FOUND"));
        verify(client, never()).fetchTools(anyString(), any());
    }

    // ------------------------------------------------------------ fixtures

    private static McpService service(String backendAuthMode) {
        return new McpService(SERVICE_ID, TENANT, "mcp-test", null, "https://mcp.example/mcp", "STREAMABLE_HTTP",
                "ONLINE", "UNKNOWN", null, 0, 0, 30, 5, 3, 1, "/health", 0, UUID.randomUUID(), Instant.now(),
                Instant.now(), backendAuthMode, "API_KEY".equals(backendAuthMode) ? Instant.now() : null,
                McpService.DEFAULT_UPSTREAM_TIMEOUT_MS);
    }

    private static McpTool tool(String name, String description) {
        return new McpTool(UUID.randomUUID(), TENANT, SERVICE_ID, name, description, "POST", "/", "ENABLED", 0,
                UUID.randomUUID(), Instant.now(), Instant.now());
    }
}
