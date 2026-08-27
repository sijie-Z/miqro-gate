package com.miqroera.miqrokey.testing.compatibility;

/**
 * Standalone entry-point for the compatibility mock server.
 *
 * <p>
 * Accepts non-secret port/capacity options via system properties or environment
 * variables. All values are plain configuration numbers; no credential or
 * secret is accepted on the command line.
 * </p>
 *
 * <h3>Configuration</h3>
 * <table>
 * <tr>
 * <th>System property</th>
 * <th>Environment variable</th>
 * <th>Default</th>
 * </tr>
 * <tr>
 * <td>{@code compatibility.mock.port}</td>
 * <td>{@code COMPATIBILITY_MOCK_PORT}</td>
 * <td>{@code 8082}</td>
 * </tr>
 * <tr>
 * <td>{@code compatibility.mock.capacity}</td>
 * <td>{@code COMPATIBILITY_MOCK_CAPACITY}</td>
 * <td>{@code 10}</td>
 * </tr>
 * </table>
 *
 * <p>
 * The server binds to {@code 127.0.0.1} only and stays alive until the process
 * is terminated (Ctrl+C). A JVM shutdown hook disposes the server cleanly.
 * </p>
 */
public final class CompatibilityMockServerMain {

    private CompatibilityMockServerMain() {
        // utility class
    }

    /**
     * Starts the compatibility mock server and blocks the main thread indefinitely.
     *
     * @param args
     *            ignored
     */
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("compatibility.mock.port",
                System.getenv().getOrDefault("COMPATIBILITY_MOCK_PORT", "8082")));
        int capacity = Integer.parseInt(System.getProperty("compatibility.mock.capacity",
                System.getenv().getOrDefault("COMPATIBILITY_MOCK_CAPACITY", "10")));
        int bodyBound = Integer.parseInt(System.getProperty("compatibility.mock.bodyBound",
                System.getenv().getOrDefault("COMPATIBILITY_MOCK_BODY_BOUND",
                        String.valueOf(CompatibilityMockServer.DEFAULT_BODY_BOUND_BYTES))));

        CompatibilityMockServer server = new CompatibilityMockServer(port, capacity, bodyBound);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down compatibility mock server...");
            server.close();
            System.out.println("Compatibility mock server stopped.");
        }, "compatibility-mock-shutdown"));

        System.out.println("Compatibility mock server started on http://127.0.0.1:" + server.getPort());
        System.out.println("Observation capacity: " + capacity);
        System.out.println("Body size bound: " + bodyBound + " bytes");
        System.out.println("Press Ctrl+C to stop.");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server.close();
    }
}
