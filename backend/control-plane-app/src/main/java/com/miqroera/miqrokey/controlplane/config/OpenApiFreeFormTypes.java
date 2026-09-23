package com.miqroera.miqrokey.controlplane.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

import java.util.Iterator;

/**
 * #1298: the MCP tool-import endpoint takes a whole OpenAPI document as
 * {@code JsonNode} — a free-form JSON value, which the committed baseline
 * declares as {@code JsonNode: {}}. Left alone, springdoc reads that type as an
 * ordinary bean and publishes Jackson's own accessors ({@code array},
 * {@code empty}, {@code pojo}, {@code nodeType}, …) as request-body fields, so
 * the served spec describes the import body as 24 flags instead of a document
 * and a client generated from it cannot express a real document. Free-form
 * stays free-form here: the empty schema is registered under the same component
 * name the baseline uses and referenced from the body, which accepts any JSON
 * value — exactly what the endpoint parses.
 */
@Configuration
public class OpenApiFreeFormTypes {

    @Bean
    ModelConverter jsonNodeStaysFreeForm() {
        return new ModelConverter() {
            @Override
            public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
                    Iterator<ModelConverter> chain) {
                if (JsonNode.class.equals(type.getType())) {
                    // Registered so the reference resolves to a component; an empty
                    // schema is what "any JSON value" looks like in OpenAPI.
                    context.defineModel("JsonNode", new Schema<>());
                    return new Schema<Object>().$ref("#/components/schemas/JsonNode");
                }
                return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
            }
        };
    }
}
