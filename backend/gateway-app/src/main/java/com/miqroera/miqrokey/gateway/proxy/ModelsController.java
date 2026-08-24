package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * {@code GET /v1/models} — returns the models the presented virtual key is
 * allowed to use, in OpenAI-compatible list format. The allowed set comes from
 * the route snapshot ({@code virtual_key_models} grants), so a key only ever
 * sees its own permissions.
 */
@RestController
public class ModelsController {

    private final VirtualKeyResolver keyResolver;
    private final ObjectMapper objectMapper;

    public ModelsController(VirtualKeyResolver keyResolver, ObjectMapper objectMapper) {
        this.keyResolver = keyResolver;
        this.objectMapper = objectMapper;
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
        for (String model : ctx.models().stream().sorted().toList()) {
            ObjectNode entry = data.addObject();
            entry.put("id", model);
            entry.put("object", "model");
            entry.put("created", 0);
            entry.put("owned_by", "miqrokey");
        }
        return root.toString();
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
