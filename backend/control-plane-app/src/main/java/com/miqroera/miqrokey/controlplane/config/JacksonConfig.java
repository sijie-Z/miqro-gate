package com.miqroera.miqrokey.controlplane.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.miqroera.miqrokey.domain.model.User;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the password hash out of every control-plane serialization point
 * (login/me/user lists): the domain stays a pure model without Jackson
 * annotations, the exclusion lives at the edge.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer userHashMixin() {
        return builder -> builder.mixIn(User.class, UserMixin.class);
    }

    abstract static class UserMixin {
        @JsonIgnore
        abstract byte[] passwordHash();
    }
}
