package com.miqroera.miqrokey.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/openapi/openapi-3.1.json} is the committed, machine-readable mirror of the Control
 * Plane contract: CI diffs it against the freshly generated document
 * ({@code deploy/openapi/check-openapi-breaking.py}) and the frontend derives
 * {@code src/types/generated.ts} from it ({@code npm run gen:types}). It therefore has to describe
 * the <em>production</em> surface only.
 *
 * <p>
 * The document is produced by {@code OpenApiSpecIntegrationTest}, which boots the application on
 * the <em>test</em> classpath — so any {@code @RestController} living in {@code src/test/java} ends
 * up in the generated document unless it is explicitly excluded. Those controllers are not part of
 * the production artifact (their own javadoc says so), so publishing them makes the contract
 * promise endpoints that answer 404 in a real deployment, and leaks test scaffolding into the
 * types the frontend compiles against.
 * </p>
 */
class OpenApiBaselineTestControllerLeakTest {

    /** Class-level mapping, tolerating {@code @RequestMapping(value = "/x")} and {@code ({"/x"})}. */
    private static final Pattern CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");

    /** Method-level verb mapping, same tolerances. Group 1 is the verb, group 2 the path. */
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    /** The operation keys an OpenAPI path item can carry. */
    private static final List<String> HTTP_METHODS = List.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final String BASELINE = "docs/openapi/openapi-3.1.json";

    @Test
    @DisplayName("the committed OpenAPI baseline exposes no operation that only exists on the test classpath")
    void baselineDoesNotLeakTestClasspathControllers() {
        Path root = repoRoot();
        Path baseline = root.resolve(BASELINE);
        Set<String> publishedOperations = publishedOperations(baseline);

        assertTrue(publishedOperations.size() > 100,
                "expected " + BASELINE + " to describe the whole Control Plane, but it lists only "
                        + publishedOperations.size() + " operations — was the document moved or regenerated elsewhere?");

        List<Path> testControllers = controllersUnder(root.resolve("backend/control-plane-app/src/test/java"));
        assertTrue(testControllers.size() >= 2,
                "expected the test-classpath controllers (AdminTestController, OwnershipTestController) but found "
                        + testControllers.size() + " — the guard below would pass vacuously");

        Set<String> productionOperations = new TreeSet<>();
        for (Path controller : controllersUnder(root.resolve("backend/control-plane-app/src/main/java"))) {
            productionOperations.addAll(mappedOperations(controller));
        }
        assertTrue(productionOperations.stream().anyMatch(publishedOperations::contains),
                "extracting @RequestMapping from src/main/java produced no operation the baseline knows, "
                        + "so the extractor — and therefore this guard — is broken");

        Set<String> leaked = new TreeSet<>();
        for (Path controller : testControllers) {
            for (String operation : mappedOperations(controller)) {
                // Compare operations, not paths: a test controller may add a verb to a path that a
                // production controller also serves, and that verb is still not in the artifact.
                if (publishedOperations.contains(operation) && !productionOperations.contains(operation)) {
                    leaked.add(operation + " (" + root.relativize(controller).toString().replace('\\', '/') + ")");
                }
            }
        }

        assertTrue(leaked.isEmpty(),
                "these operations are served only by controllers on the test classpath, yet they are published in "
                        + BASELINE + " (and therefore in frontend/src/types/generated.ts): " + leaked
                        + " — mark those controllers @Hidden (io.swagger.v3.oas.annotations.Hidden) and regenerate "
                        + "the baseline and the frontend types");
    }

    /** Every published operation, as {@code "GET /api/v1/thing"} — the unit springdoc documents. */
    private static Set<String> publishedOperations(Path baseline) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readString(baseline, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> operations = new TreeSet<>();
        for (Iterator<String> paths = root.path("paths").fieldNames(); paths.hasNext(); ) {
            String path = paths.next();
            JsonNode item = root.path("paths").path(path);
            for (String method : HTTP_METHODS) {
                if (item.path(method).isObject()) {
                    operations.add(method.toUpperCase() + " " + path);
                }
            }
        }
        return operations;
    }

    /** Every operation this controller maps, combining its class-level prefix with each verb mapping. */
    private static Set<String> mappedOperations(Path controller) {
        String code = withoutComments(read(controller));
        Matcher classLevel = CLASS_MAPPING.matcher(code);
        if (!classLevel.find()) {
            return Set.of();
        }
        String base = classLevel.group(1);
        String prefix = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        Set<String> operations = new TreeSet<>();
        for (Matcher methods = METHOD_MAPPING.matcher(code); methods.find(); ) {
            String method = methods.group(2);
            operations.add(methods.group(1).toUpperCase() + " "
                    + prefix + (method.startsWith("/") ? method : "/" + method));
        }
        return operations;
    }

    private static List<Path> controllersUnder(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(OpenApiBaselineTestControllerLeakTest::isController)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isController(Path source) {
        String code = withoutComments(read(source));
        return code.contains("@RestController") || code.contains("@Controller");
    }

    private static String withoutComments(String code) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(code).replaceAll("")).replaceAll("");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("backend").resolve("domain"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "could not locate the repository root (no backend/domain above "
                + Path.of("").toAbsolutePath() + ")");
        return candidate;
    }
}
