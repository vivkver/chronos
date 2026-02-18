package com.chronos.core.util;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Lightweight Health Check Server using JDK's HttpServer.
 * Exposes /health endpoint.
 */
public final class HealthServer {
    private static final Logger LOG = LoggerFactory.getLogger(HealthServer.class);
    private HttpServer server;
    private final CopyOnWriteArrayList<ComponentCheck> checks = new CopyOnWriteArrayList<>();

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/health", new HealthHandler());
            server.createContext("/metrics", new MetricsHandler());
            server.setExecutor(null); // default executor
            server.start();
            LOG.info("HealthServer started on port {}", port);
        } catch (IOException e) {
            LOG.error("Failed to start HealthServer", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public void register(String name, BooleanSupplier check) {
        checks.add(new ComponentCheck(name, check));
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            boolean allUp = true;
            StringBuilder response = new StringBuilder("{");

            for (int i = 0; i < checks.size(); i++) {
                ComponentCheck c = checks.get(i);
                boolean up = false;
                try {
                    up = c.check.getAsBoolean();
                } catch (Exception e) {
                    LOG.error("Health check failed for " + c.name, e);
                }

                if (!up)
                    allUp = false;

                response.append("\"").append(c.name).append("\":").append(up ? "\"UP\"" : "\"DOWN\"");
                if (i < checks.size() - 1)
                    response.append(",");
            }
            response.append("}");

            String body = response.toString();
            t.sendResponseHeaders(allUp ? 200 : 503, body.length());
            try (OutputStream os = t.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("# HELP chronos_orders_processed_total Total orders processed\n");
            sb.append("# TYPE chronos_orders_processed_total counter\n");
            sb.append("chronos_orders_processed_total ").append(ChronosMetrics.getOrdersProcessed()).append("\n");

            sb.append("# HELP chronos_orders_rejected_total Total orders rejected\n");
            sb.append("# TYPE chronos_orders_rejected_total counter\n");
            sb.append("chronos_orders_rejected_total ").append(ChronosMetrics.getOrdersRejected()).append("\n");

            sb.append("# HELP chronos_matches_found_total Total matches found\n");
            sb.append("# TYPE chronos_matches_found_total counter\n");
            sb.append("chronos_matches_found_total ").append(ChronosMetrics.getMatchesFound()).append("\n");

            String body = sb.toString();
            t.sendResponseHeaders(200, body.length());
            try (OutputStream os = t.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static class ComponentCheck {
        final String name;
        final BooleanSupplier check;

        ComponentCheck(String name, BooleanSupplier check) {
            this.name = name;
            this.check = check;
        }
    }
}
