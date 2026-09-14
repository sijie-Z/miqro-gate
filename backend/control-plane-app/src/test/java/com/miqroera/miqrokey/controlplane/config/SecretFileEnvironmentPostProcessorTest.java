package com.miqroera.miqrokey.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miqroera.miqrokey.controlplane.ControlPlaneApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class SecretFileEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private final SecretFileEnvironmentPostProcessor processor = new SecretFileEnvironmentPostProcessor();

    private StandardEnvironment environmentWith(Map<String, Object> entries) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testEnv", entries));
        return environment;
    }

    @Test
    @DisplayName("resolves the password from its _FILE companion when the plain variable is unset")
    void resolvesFromFile() throws IOException {
        Path secret = tempDir.resolve("db_password");
        Files.writeString(secret, "s3cret-from-file\n");

        StandardEnvironment environment = environmentWith(Map.of("MIQROKEY_DB_PASSWORD_FILE", secret.toString()));
        processor.postProcessEnvironment(environment, new SpringApplication(ControlPlaneApplication.class));

        assertThat(environment.getProperty("MIQROKEY_DB_PASSWORD")).isEqualTo("s3cret-from-file");
    }

    @Test
    @DisplayName("an explicitly set plain variable wins over the file companion")
    void plainVariableWins() throws IOException {
        Path secret = tempDir.resolve("db_password");
        Files.writeString(secret, "from-file\n");

        StandardEnvironment environment = environmentWith(Map.of(
                "MIQROKEY_DB_PASSWORD", "explicit", "MIQROKEY_DB_PASSWORD_FILE", secret.toString()));
        processor.postProcessEnvironment(environment, new SpringApplication(ControlPlaneApplication.class));

        assertThat(environment.getProperty("MIQROKEY_DB_PASSWORD")).isEqualTo("explicit");
    }

    @Test
    @DisplayName("a configured but unreadable file fails startup")
    void unreadableFileFailsFast() {
        StandardEnvironment environment = environmentWith(
                Map.of("MIQROKEY_DB_PASSWORD_FILE", tempDir.resolve("missing").toString()));

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment,
                new SpringApplication(ControlPlaneApplication.class))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MIQROKEY_DB_PASSWORD_FILE");
    }

    @Test
    @DisplayName("no _FILE companion leaves the environment untouched")
    void noFileIsNoOp() {
        StandardEnvironment environment = environmentWith(Map.of());

        processor.postProcessEnvironment(environment, new SpringApplication(ControlPlaneApplication.class));

        assertThat(environment.getPropertySources().contains("secretFiles")).isFalse();
    }
}
