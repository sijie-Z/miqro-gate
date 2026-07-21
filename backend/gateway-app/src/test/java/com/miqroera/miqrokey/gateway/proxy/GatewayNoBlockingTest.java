package com.miqroera.miqrokey.gateway.proxy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Verifies that production Gateway code contains no blocking calls.
 *
 * <p>
 * The Gateway runs on a Reactor Netty event loop. Blocking calls
 * ({@code .block()}, {@code .blockFirst()}, {@code .blockLast()}) would stall
 * the event loop and are forbidden in production code.
 * </p>
 */
@DisplayName("Gateway blocking call check")
class GatewayNoBlockingTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.miqroera.miqrokey.gateway");
    }

    @Test
    @DisplayName("production Gateway code must not call .block()")
    void shouldNotCallBlock() {
        ArchRule rule = noClasses().should().callMethod(reactor.core.publisher.Mono.class, "block").orShould()
                .callMethod(reactor.core.publisher.Mono.class, "block", java.time.Duration.class)
                .because("blocking on a Reactor event loop stalls the Gateway");
        rule.check(productionClasses);
    }

    @Test
    @DisplayName("production Gateway code must not call .blockFirst()")
    void shouldNotCallBlockFirst() {
        ArchRule rule = noClasses().should().callMethod(reactor.core.publisher.Flux.class, "blockFirst").orShould()
                .callMethod(reactor.core.publisher.Flux.class, "blockFirst", java.time.Duration.class)
                .because("blockFirst stalls the Reactor event loop");
        rule.check(productionClasses);
    }

    @Test
    @DisplayName("production Gateway code must not call .blockLast()")
    void shouldNotCallBlockLast() {
        ArchRule rule = noClasses().should().callMethod(reactor.core.publisher.Flux.class, "blockLast").orShould()
                .callMethod(reactor.core.publisher.Flux.class, "blockLast", java.time.Duration.class)
                .because("blockLast stalls the Reactor event loop");
        rule.check(productionClasses);
    }
}
