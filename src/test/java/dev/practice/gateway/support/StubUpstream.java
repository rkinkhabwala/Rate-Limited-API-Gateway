package dev.practice.gateway.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake backend service built on the JDK's own HTTP server (no extra dependency).
 * Echoes what it received so tests can assert what the gateway forwarded.
 * Any path containing "/fail" answers 500.
 */
public final class StubUpstream {

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    private StubUpstream(HttpServer server) {
        this.server = server;
    }

    public static StubUpstream start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubUpstream stub = new StubUpstream(server);
            server.createContext("/", exchange -> {
                stub.hits.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
                String plan = exchange.getRequestHeaders().getFirst("X-Client-Plan");
                boolean keyForwarded = exchange.getRequestHeaders().containsKey("X-API-Key");
                int status = path.contains("/fail") ? 500 : 200;
                String body = "{\"path\":\"" + path + "\",\"clientId\":\"" + clientId + "\",\"clientPlan\":\""
                        + plan + "\",\"apiKeyForwarded\":" + keyForwarded + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(16));
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start stub upstream", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hits() {
        return hits.get();
    }
}
