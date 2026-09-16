package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import com.miqroera.miqrokey.gateway.vkey.VirtualKeyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code GET /v1/context-registry} (CAA Spec v1.1 §8): the repo → project
 * mapping the local agent uses to turn local evidence (git remotes, PR URLs)
 * into a project claim.
 *
 * <p>
 * Scope is deliberately tight: only mappings of projects the presented virtual
 * key holds an ACTIVE binding for are returned, so the agent can only ever
 * claim projects this key may use. Registry reads are infrequent (agent sync,
 * not the request hot path), hence the direct database read; without
 * persistence enabled the registry is empty.
 * </p>
 */
@RestController
public class ContextRegistryController {

    private final VirtualKeyResolver keyResolver;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider;
    private final ObjectMapper objectMapper;

    public ContextRegistryController(VirtualKeyResolver keyResolver,
            ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider, ObjectMapper objectMapper) {
        this.keyResolver = keyResolver;
        this.jdbcProvider = jdbcProvider;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/v1/context-registry")
    public Mono<Void> registry(ServerWebExchange exchange) {
        try {
            // Identity-only (#641): registry reads must not require a resolved
            // request context — for a multi-bound key that would be circular
            // (the agent needs the registry to produce the context).
            VirtualKeyResolver.Identity identity = keyResolver.resolveIdentity(exchange.getRequest());
            String body = buildBody(identity);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
        } catch (AuthFailureException e) {
            exchange.getResponse().setStatusCode(HttpStatus.valueOf(e.status()));
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = ErrorEnvelopes.body(e, exchange.getRequest().getURI().getPath())
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        }
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
            var rows = jdbc.query("""
                    SELECT project_id, repo_key FROM project_repositories
                    WHERE tenant_id = :tenantId AND project_id IN (:projectIds)
                    ORDER BY repo_key
                    """,
                    new MapSqlParameterSource("tenantId", identity.key().tenantId()).addValue("projectIds", projectIds),
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
}
