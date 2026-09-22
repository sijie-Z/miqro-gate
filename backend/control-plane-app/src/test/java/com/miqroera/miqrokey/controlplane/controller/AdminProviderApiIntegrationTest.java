package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin provider/Plan APIs against real PostgreSQL (G5.3): product listing,
 * subscription CRUD and seat assignment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin provider/Plan API integration tests (PostgreSQL)")
class AdminProviderApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("products list carries provider, protocol and balance authority")
    void productList() throws Exception {
        fx.insertProviderAndProduct();

        mockMvc.perform(get("/api/v1/admin/provider-products").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerName").value("Test Provider"))
                .andExpect(jsonPath("$[0].productCode").value("test-product"))
                .andExpect(jsonPath("$[0].baseUrlHost").value("api.test.example"))
                .andExpect(jsonPath("$[0].balanceAuthority").value("OFFICIAL_API"));
    }

    @Test
    @DisplayName("subscription create/update and seat assignment flow")
    void subscriptionAndSeats() throws Exception {
        fx.insertProviderAndProduct();

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(),
                                "name", "Team Plan", "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM",
                                "subscriptionPrice", 199, "currency", "USD"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Team Plan"))
                .andExpect(jsonPath("$.planScope").value("TEAM")).andReturn();
        String subscriptionId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // A user to assign the seat to.
        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "seat-user"))))
                .andExpect(status().isOk()).andReturn();
        String userId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        MvcResult seat = mockMvc
                .perform(post("/api/v1/admin/subscriptions/" + subscriptionId + "/seats")
                        .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("assignedUserId", userId, "displayName", "Alice"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seatStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.username").value("seat-user")).andReturn();
        Map<?, ?> seatBody = objectMapper.readValue(seat.getResponse().getContentAsString(), Map.class);
        String seatId = seatBody.get("id").toString();
        long seatVersion = ((Number) seatBody.get("version")).longValue();

        // Release the seat (#1133): the PATCH is partial and carries the version it was
        // read at — the release clears the assignee but keeps display_name.
        Map<String, Object> release = new java.util.HashMap<>();
        release.put("status", "AVAILABLE");
        release.put("version", seatVersion);
        mockMvc.perform(patch("/api/v1/admin/subscriptions/" + subscriptionId + "/seats/" + seatId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(release)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seatStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.assignedUserId").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/subscriptions").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Test Product"));
    }

    @Test
    @DisplayName("#1134: a PATCH must persist every field its 200 answers with")
    void subscriptionPatchPersistsEveryFieldItAnswers() throws Exception {
        fx.insertProviderAndProduct();

        MvcResult created = mockMvc.perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(), "name",
                        "Team Plan", "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM", "subscriptionPrice", 199,
                        "currency", "USD"))))
                .andExpect(status().isOk()).andReturn();
        String subscriptionId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(
                patch("/api/v1/admin/subscriptions/" + subscriptionId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Team Plan v2", "subscriptionPrice",
                                299.50, "currency", "CNY", "quotaTotal", 12345, "quotaUnit", "tokens"))))
                .andExpect(status().isOk());

        // The row is the truth, not the response body: four of the request's six fields
        // were
        // missing from the UPDATE's SET list, so the 200 echoed values the table never
        // took.
        // Asserting the response alone would have passed on the broken build — which is
        // what
        // the operator saw too ("改了没保存", with nothing to grep for).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, subscription_price, currency, quota_total, quota_unit FROM upstream_subscriptions"
                        + " WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(subscriptionId)));
        assertThat(row.get("name")).isEqualTo("Team Plan v2");
        // numeric(20,10): compare by value, not by scale — 299.50 and 299.5 are one
        // row.
        assertThat((BigDecimal) row.get("subscription_price")).isEqualByComparingTo("299.50");
        assertThat(row.get("currency")).isEqualTo("CNY");
        assertThat(((Number) row.get("quota_total")).longValue()).isEqualTo(12345L);
        assertThat(row.get("quota_unit")).isEqualTo("tokens");

        // …and the read path the operator actually looks at has to agree with that row.
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + subscriptionId).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Team Plan v2"))
                .andExpect(jsonPath("$.subscriptionPrice").value(299.50)).andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.quotaTotal").value(12345)).andExpect(jsonPath("$.quotaUnit").value("tokens"));
    }

    // ------------------------------------------------------------------
    // subscription period write path (#1330)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1330: a create writes the subscription period it was given into the row")
    void subscriptionCreatePersistsPeriodColumns() throws Exception {
        fx.insertProviderAndProduct();

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("providerProductId", fx.productId.toString());
        body.put("name", "Period Plan");
        body.put("billingMode", "FIXED_SUBSCRIPTION");
        body.put("planScope", "PERSONAL");
        body.put("subscriptionPrice", 100);
        body.put("currency", "USD");
        body.put("quotaTotal", 1000);
        body.put("quotaUnit", "TOKENS");
        body.put("periodStart", "2026-08-01T00:00:00Z");
        body.put("periodEnd", "2026-09-01T00:00:00Z");
        body.put("renewalAt", "2026-09-01T00:00:00Z");

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> createdBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        UUID subscriptionId = UUID.fromString(createdBody.get("id").toString());
        assertThat(createdBody.get("periodStart")).as("response periodStart").isNotNull();
        assertThat(createdBody.get("periodEnd")).as("response periodEnd").isNotNull();
        assertThat(createdBody.get("renewalAt")).as("response renewalAt").isNotNull();
        assertThat(Instant.parse((String) createdBody.get("periodStart")))
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));

        // The row is the truth, not the response body (#1134): the create used to
        // hardwrite NULL into these three columns whatever the request carried.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT period_start, period_end, renewal_at FROM upstream_subscriptions WHERE id = :id",
                new MapSqlParameterSource("id", subscriptionId));
        assertThat(((java.sql.Timestamp) row.get("period_start")).toInstant())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("period_end")).toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("renewal_at")).toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("#1330: a create without a period keeps the three columns NULL — no window is invented")
    void subscriptionCreateWithoutPeriodKeepsColumnsNull() throws Exception {
        fx.insertProviderAndProduct();

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(),
                                "name", "No Period", "billingMode", "PAYG", "planScope", "NONE"))))
                .andExpect(status().isOk()).andReturn();
        UUID subscriptionId = UUID.fromString(objectMapper
                .readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT period_start, period_end, renewal_at FROM upstream_subscriptions WHERE id = :id",
                new MapSqlParameterSource("id", subscriptionId));
        assertThat(row.get("period_start")).isNull();
        assertThat(row.get("period_end")).isNull();
        assertThat(row.get("renewal_at")).isNull();
    }

    @Test
    @DisplayName("#1330: a PATCH writes the period it carries, and one that omits the period keeps the stored one")
    void subscriptionPatchPersistsPeriodColumns() throws Exception {
        fx.insertProviderAndProduct();

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(),
                                "name", "Team Plan", "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM",
                                "subscriptionPrice", 199, "currency", "USD"))))
                .andExpect(status().isOk()).andReturn();
        UUID subscriptionId = UUID.fromString(objectMapper
                .readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString());

        Map<String, Object> patchBody = new java.util.HashMap<>();
        patchBody.put("periodStart", "2026-08-01T00:00:00Z");
        patchBody.put("periodEnd", "2026-09-01T00:00:00Z");
        patchBody.put("renewalAt", "2026-08-25T00:00:00Z");
        MvcResult patched = mockMvc
                .perform(patch("/api/v1/admin/subscriptions/" + subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(patchBody)))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> patchedBody = objectMapper.readValue(patched.getResponse().getContentAsString(), Map.class);
        assertThat(patchedBody.get("periodStart")).as("response periodStart").isNotNull();
        assertThat(Instant.parse((String) patchedBody.get("periodStart")))
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(Instant.parse((String) patchedBody.get("renewalAt")))
                .isEqualTo(Instant.parse("2026-08-25T00:00:00Z"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT period_start, period_end, renewal_at FROM upstream_subscriptions WHERE id = :id",
                new MapSqlParameterSource("id", subscriptionId));
        assertThat(((java.sql.Timestamp) row.get("period_start")).toInstant())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("period_end")).toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("renewal_at")).toInstant())
                .isEqualTo(Instant.parse("2026-08-25T00:00:00Z"));

        // A later PATCH that says nothing about the period keeps it — the same
        // null-means-keep merge every other optional field of this PATCH uses.
        mockMvc.perform(patch("/api/v1/admin/subscriptions/" + subscriptionId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", "Team Plan v2"))))
                .andExpect(status().isOk());
        row = jdbc.queryForMap("SELECT period_start, renewal_at FROM upstream_subscriptions WHERE id = :id",
                new MapSqlParameterSource("id", subscriptionId));
        assertThat(((java.sql.Timestamp) row.get("period_start")).toInstant())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("renewal_at")).toInstant())
                .isEqualTo(Instant.parse("2026-08-25T00:00:00Z"));
    }

    // ------------------------------------------------------------------
    // audit summary escaping (#1230, rc.20 prepatch)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1230: a quoted subscription name is accepted and round-trips through the audit summary")
    void subscriptionNameWithQuoteIsAcceptedAndAudited() throws Exception {
        fx.insertProviderAndProduct();
        String name = "VIP \"Gold\"";

        // The name lands verbatim in the change_summary jsonb cast; a raw quote used
        // to make PostgreSQL reject it (22P02, surfaced as 409 RESOURCE_CONFLICT),
        // failing the request.
        mockMvc.perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(), "name",
                        name, "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM", "subscriptionPrice", 199,
                        "currency", "USD"))))
                .andExpect(status().isOk());

        JsonNode summary = objectMapper.readTree(latestSummary("SUBSCRIPTION_CREATE"));
        assertThat(summary.get("name").asText()).isEqualTo(name);
        assertThat(summary.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("#1230: a crafted subscription name cannot forge audit summary members")
    void craftedSubscriptionNameCannotForgeMembers() throws Exception {
        fx.insertProviderAndProduct();
        String crafted = "x\",\"forged\":\"y";

        mockMvc.perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(), "name",
                        crafted, "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM", "subscriptionPrice", 199,
                        "currency", "USD"))))
                .andExpect(status().isOk());

        JsonNode summary = objectMapper.readTree(latestSummary("SUBSCRIPTION_CREATE"));
        assertThat(summary.get("forged")).isNull();
        assertThat(summary.get("name").asText()).isEqualTo(crafted);
    }

    @Test
    @DisplayName("#1230: a quoted seat displayName is accepted and round-trips through the audit summary")
    void seatDisplayNameWithQuoteIsAcceptedAndAudited() throws Exception {
        fx.insertProviderAndProduct();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(), "name",
                        "Seat Plan", "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM", "subscriptionPrice", 199,
                        "currency", "USD"))))
                .andExpect(status().isOk()).andReturn();
        String subscriptionId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();
        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "seat-user"))))
                .andExpect(status().isOk()).andReturn();
        String userId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        String displayName = "Alice \"Ace\"";
        // The SEAT_CREATE summary carries the display name; the raw quote used to
        // make the jsonb cast fail (22P02, surfaced as 409 RESOURCE_CONFLICT).
        mockMvc.perform(
                post("/api/v1/admin/subscriptions/" + subscriptionId + "/seats").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("assignedUserId", userId, "displayName", displayName))))
                .andExpect(status().isOk());

        assertThat(objectMapper.readTree(latestSummary("SEAT_CREATE")).get("displayName").asText())
                .isEqualTo(displayName);
    }

    private String latestSummary(String action) {
        return jdbc.queryForObject(
                "SELECT change_summary::text FROM admin_audit_events WHERE action = :action "
                        + "ORDER BY chain_position DESC LIMIT 1",
                new MapSqlParameterSource("action", action), String.class);
    }

    // ------------------------------------------------------------------
    // seat PATCH semantics (#1133)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1133: a PATCH carrying only displayName keeps the assignee and the status")
    void seatPatchWithOnlyDisplayNameKeepsTheRest() throws Exception {
        SeatFixture seat = givenAssignedSeat();

        mockMvc.perform(seatPatch(seat, Map.of("displayName", "Alice Zhang"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Zhang"))
                // The request never mentioned these, so they must survive untouched. The
                // pre-fix code wrote every column it knew about, nulling whatever the
                // request omitted — and it dereferenced the missing status first, so this
                // path answered 500.
                .andExpect(jsonPath("$.seatStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignedUserId").value(seat.assignedUserId()));
    }

    @Test
    @DisplayName("#1133: releasing clears the assignee but keeps the seat's display name")
    void seatReleaseKeepsDisplayName() throws Exception {
        SeatFixture seat = givenAssignedSeat();

        mockMvc.perform(seatPatch(seat, Map.of("status", "AVAILABLE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.seatStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.assignedUserId").doesNotExist())
                // The label is the operator's own note about that seat; releasing the seat
                // is not a reason to throw it away.
                .andExpect(jsonPath("$.displayName").value("Alice"));
    }

    @Test
    @DisplayName("#1133: assigning carries over to another user without touching the name")
    void seatPatchWithOnlyAssigneeMovesTheSeat() throws Exception {
        SeatFixture seat = givenAssignedSeat();
        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "seat-user-2"))))
                .andExpect(status().isOk()).andReturn();
        String secondUserId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        mockMvc.perform(seatPatch(seat, Map.of("assignedUserId", secondUserId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUserId").value(secondUserId))
                .andExpect(jsonPath("$.seatStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.displayName").value("Alice"));
    }

    @Test
    @DisplayName("#1133: a stale version is a 409 and leaves the row alone")
    void seatPatchWithStaleVersionIsAConflict() throws Exception {
        SeatFixture seat = givenAssignedSeat();

        mockMvc.perform(seatPatch(seat, Map.of("status", "DISABLED", "version", seat.version() + 99)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        // The conflict must be a refusal, not a half-applied write.
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + seat.subscriptionId() + "/seats").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].seatStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].displayName").value("Alice"));
    }

    @Test
    @DisplayName("#1133: a PATCH without version is refused as a bad request, not a 500")
    void seatPatchWithoutVersionIsABadRequest() throws Exception {
        SeatFixture seat = givenAssignedSeat();

        // `version` is declared required in the OpenAPI schema, so it has to be
        // rejected —
        // and rejected as a 400. @NotNull on the record is inert unless the controller
        // marks the body @Valid, which is how this used to answer 500 instead.
        mockMvc.perform(patch("/api/v1/admin/subscriptions/" + seat.subscriptionId() + "/seats/" + seat.seatId())
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isBadRequest());

        // …and nothing was written on the way out.
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + seat.subscriptionId() + "/seats").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].seatStatus").value("ASSIGNED"));
    }

    @Test
    @DisplayName("#1133: an ASSIGNED seat with nobody on it is refused, not written")
    void seatPatchCannotLandOnAnAssignedSeatWithNoMember() throws Exception {
        SeatFixture seat = givenAssignedSeat();
        // Release first, so the seat is AVAILABLE with no assignee.
        mockMvc.perform(seatPatch(seat, Map.of("status", "AVAILABLE"))).andExpect(status().isOk());

        SeatFixture released = withCurrentVersion(seat);
        // Asking for ASSIGNED without naming anyone used to write a row that is
        // ASSIGNED
        // and unassigned at once — the state the biconditional exists to forbid.
        mockMvc.perform(seatPatch(released, Map.of("status", "ASSIGNED"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SEAT_ASSIGNEE_MISMATCH"));
    }

    @Test
    @DisplayName("#1133: naming a member on a seat that will not be ASSIGNED is refused")
    void seatPatchCannotNameAMemberWithoutAssigningTheSeat() throws Exception {
        SeatFixture seat = givenAssignedSeat();
        mockMvc.perform(seatPatch(seat, Map.of("status", "AVAILABLE"))).andExpect(status().isOk());
        SeatFixture released = withCurrentVersion(seat);

        // Both shapes used to answer 200 and drop the member: the guard could only ever
        // fire from the other end, and the assignee is nulled whenever the seat is not
        // ASSIGNED.
        mockMvc.perform(seatPatch(released, Map.of("assignedUserId", seat.assignedUserId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEAT_ASSIGNEE_MISMATCH"));
        mockMvc.perform(seatPatch(released, Map.of("assignedUserId", seat.assignedUserId(), "status", "AVAILABLE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEAT_ASSIGNEE_MISMATCH"));

        // Neither refusal wrote anything, and a plain release still works.
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + seat.subscriptionId() + "/seats").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].seatStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].assignedUserId").doesNotExist());
    }

    /**
     * A subscription holding one ASSIGNED seat labelled "Alice" — the fixture every
     * seat patch test starts from.
     */
    private SeatFixture givenAssignedSeat() throws Exception {
        fx.insertProviderAndProduct();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(), "name",
                        "Seat Plan", "billingMode", "FIXED_SUBSCRIPTION", "planScope", "TEAM", "subscriptionPrice", 199,
                        "currency", "USD"))))
                .andExpect(status().isOk()).andReturn();
        String subscriptionId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "seat-user"))))
                .andExpect(status().isOk()).andReturn();
        String userId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        MvcResult seat = mockMvc
                .perform(post("/api/v1/admin/subscriptions/" + subscriptionId + "/seats")
                        .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("assignedUserId", userId, "displayName", "Alice"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seatStatus").value("ASSIGNED")).andReturn();
        Map<?, ?> seatBody = objectMapper.readValue(seat.getResponse().getContentAsString(), Map.class);
        return new SeatFixture(subscriptionId, seatBody.get("id").toString(),
                ((Number) seatBody.get("version")).longValue(), userId);
    }

    /**
     * A PATCH to that seat with {@code version} added — the body shape the API
     * requires. A caller that supplies its own {@code version} keeps it: the
     * stale-version test sends one this fixture did not read.
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder seatPatch(SeatFixture seat,
            Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(fields);
        body.putIfAbsent("version", seat.version());
        return patch("/api/v1/admin/subscriptions/" + seat.subscriptionId() + "/seats/" + seat.seatId())
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(body));
    }

    private record SeatFixture(String subscriptionId, String seatId, long version, String assignedUserId) {
    }

    /**
     * The same seat, re-read: a write bumps the version, so a later patch needs the
     * new one.
     */
    private SeatFixture withCurrentVersion(SeatFixture seat) throws Exception {
        MvcResult seats = mockMvc
                .perform(get("/api/v1/admin/subscriptions/" + seat.subscriptionId() + "/seats").cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        List<?> rows = objectMapper.readValue(seats.getResponse().getContentAsString(), List.class);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        return new SeatFixture(seat.subscriptionId(), seat.seatId(), ((Number) row.get("version")).longValue(), null);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("quota_snapshots", "cost_allocations", "usage_event", "cache_hit_event",
                    "price_snapshot", "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                    "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                    "project_memberships", "team_memberships", "project_repositories", "projects", "teams",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertProviderAndProduct() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status,
                         balance_authority, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}',
                            'VERIFIED', 'OFFICIAL_API', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
        }
    }

    static class BootstrapHelper {
        static final java.nio.file.Path SECRET_FILE;
        static final String SECRET = "test-bootstrap-secret-min-16chars";
        static {
            try {
                SECRET_FILE = java.nio.file.Files.createTempFile("bootstrap-secret", ".txt");
                java.nio.file.Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        static java.nio.file.Path secretFile() {
            return SECRET_FILE;
        }
        static String secret() {
            return SECRET;
        }
    }
}
