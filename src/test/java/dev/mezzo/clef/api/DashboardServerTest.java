package dev.mezzo.clef.api;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DashboardServerTest {

    @Test
    void servesDashboardPageWithWsPortInjected() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        DashboardServer d = new DashboardServer("127.0.0.1", port, 8731);
        d.start();
        try {
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> r = c.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("MezzoSopranoClef"), "page should be the dashboard");
            assertTrue(r.body().contains("const WS_PORT = 8731"), "ws port should be injected");
            assertFalse(r.body().contains("{{WS_PORT}}"), "placeholder must be substituted");

            HttpResponse<String> miss = c.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, miss.statusCode());
        } finally {
            d.stop();
        }
    }

    @Test
    void renderPageSubstitutesPort() throws Exception {
        String html = new String(new DashboardServer("x", 1, 9999).renderPage(), StandardCharsets.UTF_8);
        assertTrue(html.contains("const WS_PORT = 9999"));
        assertFalse(html.contains("{{WS_PORT}}"));
    }
}
