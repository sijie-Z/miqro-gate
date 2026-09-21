package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.domain.vkey.VirtualKeyParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1139: every ruling the ladder produces carries the candidate cardinality
 * (the key's ACTIVE binding count at resolution time). The fact the console
 * could not see: {@code RESOLVED_SUFFIX} mixes "the suffix picked among several
 * bindings" (a real choice) with "the suffix merely matched the only binding"
 * (no candidate existed) — both must be distinguishable by the count alone.
 *
 * <p>
 * The count is a server-side fact of the snapshot the ladder ran against; it is
 * recorded, never judged — the ladder order and outcomes are unchanged.
 * </p>
 */
@Tag("unit")
@DisplayName("RequestContextResolver — resolution candidate cardinality (#1139)")
class RequestContextResolverCandidatesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID GRANT_A = UUID.randomUUID();
    private static final UUID GRANT_B = UUID.randomUUID();
    private static final UUID CRED_A = UUID.randomUUID();
    private static final UUID CRED_B = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();
    private static final UUID BUCKET = UUID.randomUUID();

    private final RequestContextResolver resolver = new RequestContextResolver();

    private RouteSnapshot snapshotWith(Map<String, RouteSnapshot.BindingRecord> bindings) {
        return snapshotWith(bindings, Map.of());
    }

    private RouteSnapshot snapshotWith(Map<String, RouteSnapshot.BindingRecord> bindings,
            Map<UUID, RouteSnapshot.UnattributedPolicyRecord> policies) {
        RouteSnapshot.KeyRecord key = new RouteSnapshot.KeyRecord(KEY_ID, TENANT, USER, "pub-1", new byte[32],
                "DISABLED", "CLAUDE_CODE", GRANT_A);
        return new RouteSnapshot(1, Instant.EPOCH, Map.of("pub-1", key), Map.of(KEY_ID, bindings),
                Map.of(CRED_A, credential(CRED_A), CRED_B, credential(CRED_B)), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), policies, Map.of(), Map.of());
    }

    private static RouteSnapshot.CredentialRecord credential(UUID id, UUID project) {
        return new RouteSnapshot.CredentialRecord(id, TENANT, PRODUCT, "https://api.test.example", "{}", null,
                Map.of());
    }

    private static RouteSnapshot.BindingRecord binding(UUID project, String tag, UUID credential, UUID grant) {
        return new RouteSnapshot.BindingRecord(KEY_ID, project, tag, credential, PRODUCT, grant);
    }

    private static RouteSnapshot.KeyRecord keyOf(RouteSnapshot snapshot) {
        return snapshot.key("pub-1");
    }

    private static VirtualKeyParseResult parsedWith(String tag) {
        return new VirtualKeyParseResult("pub-1", new byte[32], tag, true);
    }

    private RouteSnapshot twoBindings() {
        return snapshotWith(Map.of("tag-a", binding(PROJECT_A, "tag-a", CRED_A, GRANT_A), "tag-b",
                binding(PROJECT_B, "tag-b", CRED_B, GRANT_B)));
    }

    private RouteSnapshot soleBinding() {
        return snapshotWith(Map.of("tag-a", binding(PROJECT_A, "tag-a", CRED_A, GRANT_A)));
    }

    @Test
    @DisplayName("a suffix hit on a multi-binding key is a real choice: 2 candidates (RESOLVED_SUFFIX)")
    void multiBindingSuffixHitReportsCandidates() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("tag-b"), request);

        assertThat(context.resolutionStatus()).isEqualTo("RESOLVED_SUFFIX");
        assertThat(context.binding().projectId()).isEqualTo(PROJECT_B);
        assertThat(context.resolutionCandidates()).isEqualTo(2);
    }

    @Test
    @DisplayName("a suffix hit on a sole-binding key is no choice at all: 1 candidate (RESOLVED_SUFFIX)")
    void soleBindingSuffixHitReportsOneCandidate() {
        RouteSnapshot snapshot = soleBinding();

        ResolvedContext context = resolver
                .resolve(snapshot, keyOf(snapshot), parsedWith("tag-a"), MockServerHttpRequest.get("/v1/messages").build());

        assertThat(context.resolutionStatus()).isEqualTo("RESOLVED_SUFFIX");
        assertThat(context.resolutionCandidates()).isEqualTo(1);
    }

    @Test
    @DisplayName("the sole-binding fallback records the same 1 candidate as the suffix hit on that key")
    void soleBindingFallbackReportsOneCandidate() {
        RouteSnapshot snapshot = soleBinding();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.resolutionStatus()).isEqualTo("SOLE_BINDING");
        assertThat(context.resolutionCandidates()).isEqualTo(1);
    }

    @Test
    @DisplayName("a header-resolved request records the key's whole candidate set")
    void headerClaimReportsCandidates() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages")
                .header(RequestContextResolver.PROJECT_ID_HEADER, PROJECT_B.toString()).build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.resolutionStatus()).isEqualTo("RESOLVED_HEADER");
        assertThat(context.resolutionCandidates()).isEqualTo(2);
    }

    @Test
    @DisplayName("a policy-routed request records the candidates it could not attribute")
    void policyRoutedReportsCandidates() {
        RouteSnapshot snapshot = snapshotWith(
                Map.of("tag-a", binding(PROJECT_A, "tag-a", CRED_A, GRANT_A), "tag-b",
                        binding(PROJECT_B, "tag-b", CRED_B, GRANT_B)),
                Map.of(TENANT, new RouteSnapshot.UnattributedPolicyRecord(TENANT, BUCKET, CRED_A, PRODUCT, Set.of())));
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.resolutionStatus()).isEqualTo("POLICY_ROUTED");
        assertThat(context.resolutionCandidates()).isEqualTo(2);
    }
}
