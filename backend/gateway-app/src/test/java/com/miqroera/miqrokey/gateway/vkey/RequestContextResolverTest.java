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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CAA (Spec v1.1 §4) resolution-ladder contract: project-id claim → legacy
 * suffix → sole binding; several bindings without context fail closed; claims
 * are captured as audit metadata only.
 */
@Tag("unit")
@DisplayName("RequestContextResolver — CAA resolution ladder (#633)")
class RequestContextResolverTest {

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

    private final RequestContextResolver resolver = new RequestContextResolver();

    private static final UUID BUCKET = UUID.randomUUID();

    private RouteSnapshot snapshotWith(Map<String, RouteSnapshot.BindingRecord> bindings) {
        return snapshotWith(bindings, Map.of());
    }

    private RouteSnapshot snapshotWith(Map<String, RouteSnapshot.BindingRecord> bindings,
            Map<UUID, RouteSnapshot.UnattributedPolicyRecord> policies) {
        RouteSnapshot.KeyRecord key = new RouteSnapshot.KeyRecord(KEY_ID, TENANT, USER, "pub-1", new byte[32],
                "DISABLED", "CLAUDE_CODE", GRANT_A);
        return new RouteSnapshot(1, Instant.EPOCH, Map.of("pub-1", key), Map.of(KEY_ID, bindings),
                Map.of(CRED_A, credential(CRED_A, PROJECT_A), CRED_B, credential(CRED_B, PROJECT_B)), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), policies, java.util.Map.of(),
                java.util.Map.of());
    }

    private static RouteSnapshot.CredentialRecord credential(UUID id, UUID project) {
        return new RouteSnapshot.CredentialRecord(id, TENANT, PRODUCT, "https://api.test.example", "{}", null,
                Map.of());
    }

    private static RouteSnapshot.BindingRecord binding(UUID project, String tag, UUID credential, UUID grant) {
        return new RouteSnapshot.BindingRecord(KEY_ID, project, tag, credential, PRODUCT, grant);
    }

    private RouteSnapshot twoBindings() {
        return snapshotWith(Map.of("tag-a", binding(PROJECT_A, "tag-a", CRED_A, GRANT_A), "tag-b",
                binding(PROJECT_B, "tag-b", CRED_B, GRANT_B)));
    }

    private static RouteSnapshot.KeyRecord keyOf(RouteSnapshot snapshot) {
        return snapshot.key("pub-1");
    }

    private static VirtualKeyParseResult parsedWith(String tag) {
        return new VirtualKeyParseResult("pub-1", new byte[32], tag, true);
    }

    @Test
    @DisplayName("a validated project-id claim selects the binding (RESOLVED_HEADER)")
    void headerClaimSelectsBinding() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages")
                .header(RequestContextResolver.PROJECT_ID_HEADER, PROJECT_B.toString())
                .header(RequestContextResolver.ACTIVITY_HEADER, UUID.randomUUID().toString())
                .header(RequestContextResolver.CLAIM_SOURCE_HEADER, "tool_path")
                .header(RequestContextResolver.CLAIM_CONFIDENCE_HEADER, "HIGH")
                .header(RequestContextResolver.CLAIM_STATUS_HEADER, "RESOLVED")
                .header(RequestContextResolver.SESSION_ID_HEADER, "sess-1").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.resolutionStatus()).isEqualTo("RESOLVED_HEADER");
        assertThat(context.binding().projectId()).isEqualTo(PROJECT_B);
        assertThat(context.binding().grantId()).isEqualTo(GRANT_B);
        assertThat(context.claimedProjectId()).isEqualTo(PROJECT_B);
        assertThat(context.claimSource()).isEqualTo("tool_path");
        assertThat(context.claimConfidence()).isEqualTo("HIGH");
        assertThat(context.claimStatus()).isEqualTo("RESOLVED");
        assertThat(context.activityId()).isNotNull();
        assertThat(context.sessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("a claim for a project without a binding is 403 CONTEXT_NOT_ALLOWED")
    void foreignClaimIsForbidden() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages")
                .header(RequestContextResolver.PROJECT_ID_HEADER, UUID.randomUUID().toString()).build();

        assertThatThrownBy(() -> resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request))
                .isInstanceOfSatisfying(AuthFailureException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("CONTEXT_NOT_ALLOWED");
                });
    }

    @Test
    @DisplayName("a malformed project-id claim is 400 CONTEXT_INVALID")
    void malformedClaimIsRejected() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages")
                .header(RequestContextResolver.PROJECT_ID_HEADER, "not-a-uuid").build();

        assertThatThrownBy(() -> resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request))
                .isInstanceOfSatisfying(AuthFailureException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("CONTEXT_INVALID");
                });
    }

    @Test
    @DisplayName("without a claim the legacy suffix selects the binding (RESOLVED_SUFFIX)")
    void suffixSelectsBinding() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("tag-b"), request);

        assertThat(context.resolutionStatus()).isEqualTo("RESOLVED_SUFFIX");
        assertThat(context.binding().projectId()).isEqualTo(PROJECT_B);
        assertThat(context.claimedProjectId()).isNull();
    }

    @Test
    @DisplayName("a non-matching suffix on a sole-binding key resolves (SOLE_BINDING)")
    void soleBindingFallback() {
        RouteSnapshot snapshot = snapshotWith(Map.of("tag-a", binding(PROJECT_A, "tag-a", CRED_A, GRANT_A)));
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.resolutionStatus()).isEqualTo("SOLE_BINDING");
        assertThat(context.binding().projectId()).isEqualTo(PROJECT_A);
    }

    @Test
    @DisplayName("several bindings without any context fail closed (400 CONTEXT_REQUIRED)")
    void multiBindingWithoutContextIsRejected() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        assertThatThrownBy(() -> resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request))
                .isInstanceOfSatisfying(AuthFailureException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("CONTEXT_REQUIRED");
                });
    }

    @Test
    @DisplayName("several bindings without context route via the tenant policy (#647 POLICY_ROUTED)")
    void policyRoutesUnattributed() {
        RouteSnapshot snapshot = snapshotWith(
                Map.of("tag-a", binding(PROJECT_A, "tag-a", CRED_A, GRANT_A), "tag-b",
                        binding(PROJECT_B, "tag-b", CRED_B, GRANT_B)),
                Map.of(TENANT, new RouteSnapshot.UnattributedPolicyRecord(TENANT, BUCKET, CRED_A, PRODUCT, Set.of())));
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.resolutionStatus()).isEqualTo("POLICY_ROUTED");
        assertThat(context.binding().projectId()).isEqualTo(BUCKET);
        assertThat(context.binding().credentialId()).isEqualTo(CRED_A);
        assertThat(context.binding().productId()).isEqualTo(PRODUCT);
        assertThat(context.binding().grantId()).isNull();
    }

    @Test
    @DisplayName("a key without any binding stays a uniform invalid key (404)")
    void noBindingIsInvalidKey() {
        RouteSnapshot snapshot = snapshotWith(Map.of());
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages").build();

        assertThatThrownBy(() -> resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request))
                .isInstanceOfSatisfying(AuthFailureException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("virtual_key_invalid");
                });
    }

    @Test
    @DisplayName("out-of-allowlist or oversized claims are dropped, never trusted")
    void claimsAreSanitized() {
        RouteSnapshot snapshot = twoBindings();
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/messages")
                .header(RequestContextResolver.PROJECT_ID_HEADER, PROJECT_A.toString())
                .header(RequestContextResolver.CLAIM_SOURCE_HEADER, "made_up_source")
                .header(RequestContextResolver.CLAIM_CONFIDENCE_HEADER, "GODLIKE")
                .header(RequestContextResolver.CLAIM_STATUS_HEADER, "RESOLVED")
                .header(RequestContextResolver.SESSION_ID_HEADER, "x".repeat(500)).build();

        ResolvedContext context = resolver.resolve(snapshot, keyOf(snapshot), parsedWith("demo"), request);

        assertThat(context.claimSource()).isNull();
        assertThat(context.claimConfidence()).isNull();
        assertThat(context.claimStatus()).isEqualTo("RESOLVED");
        assertThat(context.sessionId()).isNull();
    }
}
