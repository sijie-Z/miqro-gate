package com.miqroera.miqrokey.gateway.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the module dependency architecture defined in
 * {@code docs/architecture.md} and {@code docs/coding-standards.md}.
 *
 * <pre>
 * domain                 ← no Spring, no database, no HTTP
 * provider-spi           ← domain
 * provider-adapters      ← provider-spi
 * persistence-postgres   ← domain
 * gateway-app            ← domain + provider-spi + provider-adapters
 * control-plane-app      ← domain + provider-spi + provider-adapters + persistence-postgres
 * test-support           ← test fixtures
 * </pre>
 *
 * <p>
 * Cross-module rules verify that gateway-app and control-plane-app have no
 * compile-scope dependency on each other. Both module JARs are on the test
 * classpath via test-scoped Maven dependencies.
 * </p>
 */
@DisplayName("Module dependency architecture")
class ModuleDependencyTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importAllClasses() {
        allClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.miqroera.miqrokey");
    }

    @Test
    @DisplayName("domain must not depend on Spring Framework")
    void domainMustNotDependOnSpring() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.domain..").should()
                .dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("domain module must remain framework-agnostic per architecture.md");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("domain must not depend on Jakarta Persistence")
    void domainMustNotDependOnJakartaPersistence() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.domain..").should()
                .dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                .because("domain module must not use JPA annotations");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("domain must not depend on Jackson")
    void domainMustNotDependOnJackson() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.domain..").should()
                .dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("domain module must not depend on serialization libraries");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("domain must not depend on HTTP or network types")
    void domainMustNotDependOnHttp() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.domain..").should()
                .dependOnClassesThat().resideInAPackage("java.net.http..").orShould().dependOnClassesThat()
                .resideInAPackage("jakarta.servlet..").orShould().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..")
                .because("domain module must not have HTTP or servlet dependencies");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("provider-spi must not depend on Spring Framework")
    void providerSpiMustNotDependOnSpring() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.spi..").should()
                .dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("provider-spi must remain framework-agnostic per architecture.md");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("provider-spi must not depend on Jackson")
    void providerSpiMustNotDependOnJackson() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.spi..").should()
                .dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..").because(
                        "provider-spi must not carry serialization concerns; catalog parsing lives in provider-adapters");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("provider-adapters must not depend on Spring Framework")
    void providerAdaptersMustNotDependOnSpring() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.adapters..").should()
                .dependOnClassesThat().resideInAPackage("org.springframework..").because(
                        "catalog loading and adapter registration are plain Java; Spring wiring happens in the app modules");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("gateway-app must not depend on persistence-postgres")
    void gatewayMustNotDependOnPersistence() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.gateway..").should()
                .dependOnClassesThat().resideInAPackage("com.miqroera.miqrokey.persistence..")
                .because("gateway-app hot path must not perform blocking database calls");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("gateway-app must not depend on control-plane-app")
    void gatewayMustNotDependOnControlPlane() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.gateway..").should()
                .dependOnClassesThat().resideInAPackage("com.miqroera.miqrokey.controlplane..")
                .because("gateway-app and control-plane-app are independent runtimes");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("control-plane-app must not depend on gateway-app")
    void controlPlaneMustNotDependOnGateway() {
        ArchRule rule = noClasses().that().resideInAPackage("com.miqroera.miqrokey.controlplane..").should()
                .dependOnClassesThat().resideInAPackage("com.miqroera.miqrokey.gateway..")
                .because("gateway-app and control-plane-app are independent runtimes");
        rule.check(allClasses);
    }
}
