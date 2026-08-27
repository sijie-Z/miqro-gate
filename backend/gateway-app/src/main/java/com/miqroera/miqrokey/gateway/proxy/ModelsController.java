package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miqroera.miqrokey.adapters.catalog.ProviderCatalog;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import com.miqroera.miqrokey.gateway.vkey.VirtualKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code GET /v1/models} — returns the models the presented virtual key is
 * allowed to use, in OpenAI-compatible list format.
 *
 * <p>
 * The allowed set is the four-way intersection of the route snapshot's
 * authorization inputs (api-contract §7.1): the signed provider catalog (the
 * product must exist there or nothing is served), the upstream models
 * ({@code model_catalog}, written only from successful official-API fetches),
 * the ACTIVE grant's models ({@code project_provider_grant_models}), and the
 * key's own models ({@code virtual_key_models}). A model missing from any layer
 * is not authorized and never leaks.
 * </p>
 *
 * <p>
 * Note: the proxy hot path pre-validates models against the KEY's models only
 * (unchanged); this endpoint applies the full intersection.
 * </p>
 */
@RestController
public class ModelsController {

    private final VirtualKeyResolver keyResolver;
    private final ObjectMapper objectMapper;
    private final ProviderCatalog providerCatalog;

    public ModelsController(VirtualKeyResolver keyResolver, ObjectMapper objectMapper,
            ProviderCatalog providerCatalog) {
        this.keyResolver = keyResolver;
        this.objectMapper = objectMapper;
        this.providerCatalog = providerCatalog;
    }

    @GetMapping("/v1/models")
    public Mono<Void> listModels(ServerWebExchange exchange) {
        try {
            AuthContext ctx = keyResolver.resolve(exchange.getRequest());
            String body = buildListBody(ctx);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
        } catch (AuthFailureException e) {
            return writeError(exchange, e);
        }
    }

    private String buildListBody(AuthContext ctx) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        for (String model : allowedModels(ctx)) {
            ObjectNode entry = data.addObject();
            entry.put("id", model);
            entry.put("object", "model");
            entry.put("created", 0);
            entry.put("owned_by", "miqrokey");
        }
        return root.toString();
    }

    /**
     * The four-way intersection. All inputs come from the snapshot the key was
     * resolved against, so a key never sees models that outlive its own grant.
     */
    private Set<String> allowedModels(AuthContext ctx) {
        RouteSnapshot snapshot = ctx.snapshot();
        String productCode = snapshot.productCode(ctx.productId());
        if (productCode == null || providerCatalog.findById(productCode).isEmpty()) {
            // Product unknown to the signed catalog: nothing is authorized.
            return Set.of();
        }
        Set<String> allowed = new TreeSet<>(ctx.models());
        allowed.retainAll(snapshot.grantModels(ctx.key().grantId()));
        allowed.retainAll(snapshot.upstreamModels(ctx.productId()));
        return allowed;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, AuthFailureException e) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(e.status()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Same envelope as the proxy hot path (ErrorEnvelopes), so every
        // endpoint fails with the uniform {"error":{"type":...,...}} shape.
        byte[] bytes = ErrorEnvelopes.body(e, exchange.getRequest().getURI().getPath())
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
