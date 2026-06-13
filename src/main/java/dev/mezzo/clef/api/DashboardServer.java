package dev.mezzo.clef.api;

import com.sun.net.httpserver.HttpServer;
import dev.mezzo.clef.MezzoClef;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Serves the built-in browser dashboard ({@code /dashboard.html}) over plain HTTP. The page is a
 * single static file; it connects back to the WebSocket control plane from the browser, so this
 * server only ever returns that one document (with the WS port substituted in). Uses the JDK's
 * built-in {@link HttpServer} — no extra dependencies.
 */
public final class DashboardServer {

    private final String host;
    private final int port;
    private final int wsPort;
    private HttpServer server;

    public DashboardServer(String host, int dashboardPort, int wsPort) {
        this.host = host;
        this.port = dashboardPort;
        this.wsPort = wsPort;
    }

    public void start() throws IOException {
        byte[] page = renderPage();
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                } else if (path.equals("/") || path.equals("/index.html")) {
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (IOException ignored) {
                // client went away
            } finally {
                exchange.close();
            }
        });
        server.start();
        MezzoClef.LOG.info("Dashboard: http://{}:{}  (drives the control plane at ws://{}:{})",
                host, port, host, wsPort);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    /** Reads the bundled page and injects the WS port the browser should connect to. */
    public byte[] renderPage() throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard.html")) {
            if (in == null) throw new IOException("dashboard.html resource not found on classpath");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return html.replace("{{WS_PORT}}", Integer.toString(wsPort)).getBytes(StandardCharsets.UTF_8);
        }
    }
}
