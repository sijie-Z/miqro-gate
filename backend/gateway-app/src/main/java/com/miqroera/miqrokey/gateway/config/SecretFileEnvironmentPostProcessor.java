package com.miqroera.miqrokey.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Implements the {@code <NAME>_FILE} convention the configuration reference
 * documents for production secrets (#479): when
 * {@code MIQROKEY_GATEWAY_DB_PASSWORD_FILE} points at a readable file and the
 * plain variable is unset, the file's trimmed content becomes the value.
 * Deployments mount secret files (Docker secrets / 0400 files) instead of
 * exporting passwords into the process environment.
 *
 * <p>
 * An explicitly set plain variable wins over its file companion; a configured
 * but unreadable file fails startup rather than silently running without a
 * password.
 * </p>
 */
public class SecretFileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Variables whose {@code _FILE} companion this application resolves. */
    private static final List<String> FILE_BACKED = List.of("MIQROKEY_GATEWAY_DB_PASSWORD");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String base : FILE_BACKED) {
            String filePath = environment.getProperty(base + "_FILE");
            if (filePath == null || filePath.isBlank() || !environment.getProperty(base, "").isBlank()) {
                continue;
            }
            try {
                resolved.put(base, Files.readString(Path.of(filePath)).strip());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot read " + base + "_FILE=" + filePath + " configured for this deployment", e);
            }
        }
        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("secretFiles", resolved));
        }
    }
}
