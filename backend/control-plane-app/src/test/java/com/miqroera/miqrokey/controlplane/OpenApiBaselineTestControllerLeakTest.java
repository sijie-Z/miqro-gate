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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/openapi/openapi-3.1.json} is the committed, machine-readable
 * mirror of the Control Plane contract: CI diffs it against the freshly
 * generated document ({@code deploy/openapi/check-openapi-breaking.py}) and the
 * frontend derives {@code src/types/generated.ts} from it
 * ({@code npm run gen:types}). It therefore has to describe the
 * <em>production</em> surface only.
 *
 * <p>
 * The document is produced by {@code OpenApiSpecIntegrationTest}, which boots
 * the application on the <em>test</em> classpath — so any
 * {@code @RestController} living in {@code src/test/java} ends up in the
 * generated document unless it is explicitly excluded. Those controllers are
 * not part of the production artifact (their own javadoc says so), so
 * publishing them makes the contract promise endpoints that answer 404 in a
 * real deployment, and leaks test scaffolding into the types the frontend
 * compiles against.
 * </p>
 */
class OpenApiBaselineTestControllerLeakTest {

    /**
     * A Spring mapping annotation as written in source: an optional verb prefix and
     * the raw argument list. Group 1 is the verb ({@code Get}, {@code Post}…) for a
     * method-specific annotation and {@code null} for a bare
     * {@code @RequestMapping}; group 2 is the argument list <em>including</em> its
     * parentheses, or {@code null} when the annotation takes no arguments.
     *
     * <p>
     * The {@code Request} branch matters: without it {@code @RequestMapping}
     * matches nothing at all — the optional verb group cannot consume
     * {@code Request} — and every controller then loses its class-level prefix. The
     * empty-green assertion caught exactly that when this pattern was written.
     * </p>
     *
     * <p>
     * Deliberately attribute-agnostic. The first version of this guard required the
     * path to be the <em>first</em> argument, so
     * {@code @GetMapping(path = "/export", produces = "text/csv")} — the form
     * {@code AdminAuditController} already uses — matched nothing at all. That is
     * the dangerous direction: a dropped mapping makes a leak look exactly like a
     * clean build.
     * </p>
     */
    private static final Pattern MAPPING = Pattern
            .compile("@(?:(Get|Post|Put|Delete|Patch)|Request)?Mapping\\b\\s*(\\([^)]*\\))?");

    /**
     * The value of a {@code path = …} / {@code value = …} attribute, wherever it
     * sits in the argument list and whether or not it is a {@code {…}} array. Group
     * 1 stops at the next attribute so that {@code produces = "text/csv"} further
     * along is not mistaken for part of the path.
     */
    private static final Pattern PATH_ATTRIBUTE = Pattern
            .compile("(?s)\\b(?:path|value)\\s*=\\s*(.*?)(?=\\s*,\\s*[A-Za-z]+\\s*=|\\z)");

    /**
     * A positional path list: {@code @GetMapping({"/x", "/y"})} — anchored to the
     * argument list start.
     */
    private static final Pattern POSITIONAL_ARRAY = Pattern.compile("^\\s*\\(\\s*\\{([^}]*)}");

    /**
     * A positional path: {@code @GetMapping("/x")} — anchored, so
     * {@code consumes = "…"} cannot match.
     */
    private static final Pattern POSITIONAL_PATH = Pattern.compile("^\\s*\\(\\s*\"([^\"]*)\"");

    /**
     * Any string literal, used to tell "no path here" from "a path this guard
     * cannot read".
     */
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]*)\"");

    /**
     * The verbs of {@code @RequestMapping(method = RequestMethod.GET)}; stops at
     * the next attribute.
     */
    private static final Pattern METHOD_ATTRIBUTE = Pattern
            .compile("(?s)\\bmethod\\b\\s*=\\s*(.*?)(?=\\s*,\\s*[A-Za-z]+\\s*=|\\z)");

    private static final Pattern VERB_NAME = Pattern.compile("\\b(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD|TRACE)\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    /**
     * A string literal, matched with possessive quantifiers so an unbalanced quote
     * cannot backtrack.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]++|\\\\.)*+\"");

    /** The operation keys an OpenAPI path item can carry. */
    private static final List<String> HTTP_METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch",
            "trace");

    private static final Set<String> ALL_VERBS = HTTP_METHODS.stream().map(String::toUpperCase)
            .collect(Collectors.toCollection(TreeSet::new));

    private static final String BASELINE = "docs/openapi/openapi-3.1.json";

    @Test
    @DisplayName("the committed OpenAPI baseline exposes no operation that only exists on the test classpath")
    void baselineDoesNotLeakTestClasspathControllers() {
        Path root = repoRoot();
        Path baseline = root.resolve(BASELINE);
        Set<String> publishedOperations = publishedOperations(baseline);

        assertTrue(publishedOperations.size() > 100,
                "expected " + BASELINE + " to describe the whole Control Plane, but it lists only "
                        + publishedOperations.size()
                        + " operations — was the document moved or regenerated elsewhere?");

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

        // Completeness: a mappable controller must yield at least one
        // operation. If it yields none, everything it maps is invisible to
        // the diff below — the failure mode where a leak passes as a clean
        // build. Scoped to files that declare a mapping, because
        // @RestControllerAdvice classes such as GlobalExceptionHandler
        // match "@RestController" by substring yet map nothing at all.
        Set<String> blindSpots = new TreeSet<>();
        for (Path controller : Stream
                .concat(controllersUnder(root.resolve("backend/control-plane-app/src/main/java")).stream(),
                        testControllers.stream())
                .filter(OpenApiBaselineTestControllerLeakTest::declaresMapping).toList()) {
            if (mappedOperations(controller).isEmpty()) {
                blindSpots.add(root.relativize(controller).toString().replace('\\', '/'));
            }
        }
        assertTrue(blindSpots.isEmpty(),
                "these controllers declare a mapping annotation, yet no operation could be read from them — "
                        + "everything they map is invisible to this guard: " + blindSpots);

        Set<String> leaked = new TreeSet<>();
        for (Path controller : testControllers) {
            for (String operation : mappedOperations(controller)) {
                // Compare operations, not paths. A test controller may add
                // a verb to a path production also serves; that verb is
                // still not in the artifact.
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

    /**
     * Every published operation, as {@code "GET /api/v1/thing"} — the unit
     * springdoc documents.
     */
    private static Set<String> publishedOperations(Path baseline) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readString(baseline, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> operations = new TreeSet<>();
        for (Iterator<String> paths = root.path("paths").fieldNames(); paths.hasNext();) {
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

    /**
     * Every operation this controller maps: its class-level prefix combined with
     * each method mapping.
     *
     * <p>
     * Fails loudly rather than returning a short set. A mapping whose path this
     * extractor cannot read — a concatenated constant, an attribute form it does
     * not know — would otherwise simply be absent from the set, and an absent
     * operation is indistinguishable from a clean baseline. Every unreadable
     * mapping is a hole in the guard, so it stops the build with the file that
     * caused it.
     * </p>
     */
    private static Set<String> mappedOperations(Path controller) {
        String code = withoutComments(read(controller));
        Set<String> operations = new TreeSet<>();
        // Every class-level path: `@RequestMapping({"/a", "/b"})` mounts the
        // whole controller twice, and both mounts are just as publishable.
        List<String> prefixes = List.of("");
        boolean first = true;
        for (Matcher mappings = MAPPING.matcher(code); mappings.find();) {
            String verb = mappings.group(1);
            String body = mappings.group(2);
            // The first bare @RequestMapping in a controller is its class-level prefix —
            // the shape
            // every controller in this module follows. A verb-specific annotation is never
            // a prefix.
            if (first && verb == null) {
                prefixes = new ArrayList<>();
                for (String base : pathsOf(controller, body)) {
                    prefixes.add(normalize(base));
                }
                if (prefixes.isEmpty()) {
                    prefixes = List.of("");
                }
                first = false;
                continue;
            }
            first = false;
            // A mapping that names no path of its own maps the class path. An
            // empty path (rather than the prefix itself) is what the
            // concatenation below expects: putting the prefix here too would
            // double it, and the doubled operation would match no baseline
            // entry — a leaked mapping hidden by this guard's own arithmetic.
            List<String> paths = pathsOf(controller, body);
            if (paths.isEmpty()) {
                paths = List.of("");
            }
            for (String prefix : prefixes) {
                for (String path : paths) {
                    String mapped = prefix + normalize(path);
                    for (String allowed : verbsOf(verb, body)) {
                        operations.add(allowed + " " + (mapped.isEmpty() ? "/" : mapped));
                    }
                }
            }
        }
        return operations;
    }

    /**
     * The paths in this annotation argument list; empty for an annotation that
     * names none and so inherits the class-level path.
     *
     * @throws IllegalStateException
     *             when the argument list obviously holds a path this guard cannot
     *             read — a concatenated constant, an unknown attribute — because
     *             dropping it would make a leaked operation look exactly like a
     *             clean baseline
     */
    private static List<String> pathsOf(Path controller, String body) {
        if (body == null) {
            return List.of();
        }
        Matcher attribute = PATH_ATTRIBUTE.matcher(body);
        if (attribute.find()) {
            List<String> paths = literalsIn(attribute.group(1));
            if (paths.isEmpty()) {
                throw unreadablePath(controller, body);
            }
            return paths;
        }
        Matcher array = POSITIONAL_ARRAY.matcher(body);
        if (array.find()) {
            return literalsIn(array.group(1));
        }
        Matcher positional = POSITIONAL_PATH.matcher(body);
        if (positional.find()) {
            return List.of(positional.group(1));
        }
        if (LITERAL.matcher(body).find()) {
            // A literal that is neither `path = …` nor the first argument: e.g. `(BASE +
            // "/x")`,
            // or an attribute such as `produces = "…"` with no path at all. Both are
            // unreadable;
            // failing beats guessing a path that would then never match the baseline.
            throw unreadablePath(controller, body);
        }
        return List.of();
    }

    private static IllegalStateException unreadablePath(Path controller, String body) {
        return new IllegalStateException("cannot read the path of the mapping " + body + " in " + controller
                + " — this guard would silently drop the operation it maps and let a leak "
                + "pass as a clean build. Write the path as a literal (`path = \"/x\"`), or teach "
                + OpenApiBaselineTestControllerLeakTest.class.getSimpleName() + " the new form.");
    }

    private static List<String> literalsIn(String expression) {
        List<String> literals = new ArrayList<>();
        for (Matcher literal = LITERAL.matcher(expression); literal.find();) {
            literals.add(literal.group(1));
        }
        return literals;
    }

    /**
     * The verbs an annotation allows: its own for {@code @GetMapping}, otherwise
     * {@code method = …}.
     */
    private static Set<String> verbsOf(String verb, String body) {
        if (verb != null) {
            return Set.of(verb.toUpperCase());
        }
        // A bare @RequestMapping maps every verb unless `method` narrows it.
        if (body == null) {
            return ALL_VERBS;
        }
        Matcher attribute = METHOD_ATTRIBUTE.matcher(body);
        if (!attribute.find()) {
            return ALL_VERBS;
        }
        Set<String> verbs = new TreeSet<>();
        for (Matcher name = VERB_NAME.matcher(attribute.group(1)); name.find();) {
            verbs.add(name.group(1));
        }
        return verbs.isEmpty() ? ALL_VERBS : verbs;
    }

    /**
     * {@code "x"}, {@code "/x/"} and {@code "x"} all normalise to {@code "/x"};
     * {@code null} to {@code ""}.
     */
    private static String normalize(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static List<Path> controllersUnder(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(OpenApiBaselineTestControllerLeakTest::isController).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean declaresMapping(Path source) {
        return MAPPING.matcher(detectionText(source)).find();
    }

    private static boolean isController(Path source) {
        String code = detectionText(source);
        return code.contains("@RestController") || code.contains("@Controller");
    }

    /**
     * Source with comments and the <em>contents</em> of string literals removed,
     * for deciding whether a file is a controller that declares mappings.
     * Extraction cannot use this — it needs the paths, which live in those literals
     * — but detection must: this guard's own assertion messages quote
     * {@code @RequestMapping} and {@code @RestController}, and on the raw text it
     * found itself.
     */
    private static String detectionText(Path source) {
        return STRING_LITERAL.matcher(withoutComments(read(source))).replaceAll("\"\"");
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
        assertTrue(candidate != null,
                "could not locate the repository root (no backend/domain above " + Path.of("").toAbsolutePath() + ")");
        return candidate;
    }
}
