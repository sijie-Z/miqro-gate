package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import com.miqroera.miqrokey.gateway.vkey.VirtualKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * {@code GET /v1/context-registry} (CAA Spec v1.1 §8): the repo → project
 * mapping the local agent uses to turn local evidence (git remotes, PR URLs)
 * into a project claim.
 *
 * <p>
 * Scope is deliberately tight: only mappings of projects the presented virtual
 * key holds an ACTIVE binding for are returned, so the agent can only ever
 * claim projects this key may use. The registry read is JDBC, so it runs on the
 * shared blocking-work scheduler with a bounded timeout (#726) — never on the
 * Reactor event loop; without persistence enabled the registry is empty.
 * </p>
 */
@RestController
public class ContextRegistryController {

    private static final Logger log = LoggerFactory.getLogger(ContextRegistryController.class);

    /** Upper bound on the blocking registry read (#726). */
    private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(10);

    /**
     * #1400: server-side bound on the registry query, strictly inside
     * {@link #REGISTRY_TIMEOUT}. The reactor timeout only stops <em>waiting</em>
     * for the blocking read — it cannot interrupt it — so without a statement-level
     * bound the lane stays checked out (and its pooled connection stays borrowed)
     * until the database itself returns. Aborting first makes the release
     * deterministic.
     */
    private static final int STATEMENT_TIMEOUT_SECONDS = Math.toIntExact(REGISTRY_TIMEOUT.toSeconds()) - 2;

    private static final String REPOSITORY_QUERY = "SELECT project_id, repo_key FROM project_repositories WHERE tenant_id = ? AND project_id IN (%s) "
            + "ORDER BY repo_key";

    private final VirtualKeyResolver keyResolver;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider;
    private final ObjectMapper objectMapper;
    private final Scheduler jdbcScheduler;

    public ContextRegistryController(VirtualKeyResolver keyResolver,
            ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider, ObjectMapper objectMapper,
            Scheduler credentialDecryptScheduler) {
        this.keyResolver = keyResolver;
        this.jdbcProvider = jdbcProvider;
        this.objectMapper = objectMapper;
        this.jdbcScheduler = credentialDecryptScheduler;
    }

    @GetMapping("/v1/context-registry")
    public Mono<Void> registry(ServerWebExchange exchange) {
        VirtualKeyResolver.Identity identity;
        try {
            // Identity-only (#641): registry reads must not require a resolved
            // request context — for a multi-bound key that would be circular
            // (the agent needs the registry to produce the context).
            identity = keyResolver.resolveIdentity(exchange.getRequest());
        } catch (AuthFailureException e) {
            return writeError(exchange, e);
        }
        // #726: buildBody hits JDBC — run it on the shared blocking-work
        // scheduler (never the event loop) under a bounded timeout, so a stalled
        // database read cannot park the transport that also carries LLM traffic.
        // #1400: the reactor timeout bounds the response, not the occupancy — the
        // read itself carries a statement timeout (see buildBody) so the lane and its
        // connection come back on their own. Both failure modes mean the same thing to
        // the caller, so they share one envelope.
        return Mono.fromCallable(() -> buildBody(identity)).subscribeOn(jdbcScheduler).timeout(REGISTRY_TIMEOUT)
                .flatMap(body -> writeJson(exchange, HttpStatus.OK, body))
                .onErrorResume(TimeoutException.class, e -> unavailable(exchange, e))
                .onErrorResume(DataAccessException.class, e -> unavailable(exchange, e));
    }

    private Mono<Void> unavailable(ServerWebExchange exchange, Throwable cause) {
        // Not silent: the envelope tells the caller "timed out", so the real cause has
        // to be recoverable from the log or a genuine database fault would be
        // indistinguishable from a slow read.
        log.warn("context registry read failed, answering context_registry_unavailable", cause);
        return writeError(exchange, new AuthFailureException(HttpStatus.SERVICE_UNAVAILABLE,
                "context_registry_unavailable", "The context registry read timed out"));
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private Mono<Void> writeError(ServerWebExchange exchange, AuthFailureException e) {
        if (!exchange.getResponse().isCommitted()) {
            exchange.getResponse().setStatusCode(HttpStatus.valueOf(e.status()));
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }
        byte[] bytes = ErrorEnvelopes.body(e, exchange.getRequest().getURI().getPath())
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private String buildBody(VirtualKeyResolver.Identity identity) {
        Map<UUID, String> tagByProject = new HashMap<>();
        for (RouteSnapshot.BindingRecord binding : identity.snapshot().bindingsOf(identity.key().keyId())) {
            tagByProject.putIfAbsent(binding.projectId(), binding.projectTag());
        }
        Set<UUID> projectIds = new LinkedHashSet<>(tagByProject.keySet());

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode entries = root.putArray("entries");
        NamedParameterJdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null && !projectIds.isEmpty()) {
            var rows = jdbc.getJdbcTemplate().query(
                    boundedStatement(identity.key().tenantId(), List.copyOf(projectIds)),
                    (rs, rowNum) -> Map.entry(rs.getObject("project_id", UUID.class), rs.getString("repo_key")));
            for (var row : rows) {
                ObjectNode entry = entries.addObject();
                entry.put("repoKey", row.getValue());
                entry.put("projectId", row.getKey().toString());
                String tag = tagByProject.get(row.getKey());
                if (tag != null) {
                    entry.put("projectTag", tag);
                }
            }
        }
        return root.toString();
    }

    /**
     * {@link NamedParameterJdbcTemplate#query} offers no hook for a query timeout,
     * so the statement is built and bound positionally here to get at the
     * {@link PreparedStatement}. The {@code IN} list is expanded from the
     * <em>cardinality</em> of {@code projectIds} — never from its text — so the
     * generated SQL carries only {@code ?} placeholders.
     */
    private static PreparedStatementCreator boundedStatement(UUID tenantId, List<UUID> projectIds) {
        String sql = REPOSITORY_QUERY.formatted(String.join(",", Collections.nCopies(projectIds.size(), "?")));
        return con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            ps.setObject(1, tenantId);
            for (int i = 0; i < projectIds.size(); i++) {
                ps.setObject(i + 2, projectIds.get(i));
            }
            return ps;
        };
    }
}
