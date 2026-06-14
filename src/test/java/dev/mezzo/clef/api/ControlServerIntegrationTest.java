package dev.mezzo.clef.api;

import com.google.gson.JsonObject;
import dev.mezzo.clef.bot.ActionManager;
import dev.mezzo.clef.bot.InputController;
import dev.mezzo.clef.bot.UseController;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.nav.BaritoneNavigator;
import dev.mezzo.clef.screenshot.ScreenshotService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spins up the real {@link ControlServer} on an ephemeral port and talks to it over a genuine
 * WebSocket connection (handshake + masked client frames + unmasked server frames). This
 * exercises the whole control-plane wire — accept loop, RFC 6455 handshake, frame codec, JSON
 * protocol, dispatcher and the {@code ping}/{@code help} commands — without needing a running
 * Minecraft client (those commands don't touch the game).
 */
class ControlServerIntegrationTest {

    @Test
    void pingAndHelpOverRealWebSocket() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        cfg.control.authToken = "";

        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String key = Base64.getEncoder().encodeToString("clef-test-keybyte".substring(0, 16)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            String handshake = readHttpHeaders(in);
            assertTrue(handshake.contains("101"), "expected 101 Switching Protocols");
            assertTrue(handshake.toLowerCase().contains("sec-websocket-accept"));

            sendMasked(out, "{\"id\":\"1\",\"cmd\":\"ping\",\"args\":{}}");
            String pong = readUntilId(in, "\"id\":\"1\"");
            assertTrue(pong.contains("\"ok\":true"), pong);
            assertTrue(pong.contains("\"pong\":true"), pong);

            sendMasked(out, "{\"id\":\"2\",\"cmd\":\"help\",\"args\":{}}");
            String help = readUntilId(in, "\"id\":\"2\"");
            assertTrue(help.contains("screenshot"), "help should list commands: " + help);
            assertTrue(help.contains("goto"));
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownCommandReturnsError() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;

        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String key = Base64.getEncoder().encodeToString("clef-test-keybyte".substring(0, 16)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            readHttpHeaders(in);

            sendMasked(out, "{\"id\":\"9\",\"cmd\":\"bogus\",\"args\":{}}");
            String resp = readUntilId(in, "\"id\":\"9\"");
            assertTrue(resp.contains("\"ok\":false"), resp);
            assertTrue(resp.contains("unknown command"), resp);
        } finally {
            server.stop();
        }
    }

    @Test
    void eventsDeliveredOnlyToSubscribers() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();

        try (Socket a = connectWs(port); Socket b = connectWs(port)) {
            a.setSoTimeout(5000);
            b.setSoTimeout(5000);

            // A subscribes to chat; B subscribes only to health.
            sendMasked(a.getOutputStream(), "{\"id\":\"1\",\"cmd\":\"subscribe\",\"args\":{\"events\":[\"chat\"]}}");
            assertTrue(readUntilId(a.getInputStream(), "\"id\":\"1\"").contains("subscribed"));
            sendMasked(b.getOutputStream(), "{\"id\":\"2\",\"cmd\":\"subscribe\",\"args\":{\"events\":[\"health\"]}}");
            readUntilId(b.getInputStream(), "\"id\":\"2\"");

            JsonObject data = new JsonObject();
            data.addProperty("text", "hello-subscribers");
            server.emitEvent("chat", data);

            // A (subscribed to chat) receives it...
            String ev = readServerFrame(a.getInputStream());
            assertTrue(ev.contains("\"event\":\"chat\"") && ev.contains("hello-subscribers"), ev);

            // ...B (only health) does not — the read should time out.
            b.setSoTimeout(700);
            assertThrows(SocketTimeoutException.class, () -> readServerFrame(b.getInputStream()));
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsUnexpectedBrowserOrigin() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            String key = Base64.getEncoder().encodeToString("clef-test-keybyte".substring(0, 16)
                    .getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write(("GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Origin: https://evil.example\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String resp = readHttpHeaders(s.getInputStream());
            assertTrue(resp.contains("403"), resp);
        } finally {
            server.stop();
        }
    }

    @Test
    void allowsDashboardOriginWhenDashboardEnabled() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        int dashboardPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            dashboardPort = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        cfg.control.dashboard = true;
        cfg.control.dashboardPort = dashboardPort;
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            String key = Base64.getEncoder().encodeToString("clef-test-keybyte".substring(0, 16)
                    .getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write(("GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Origin: http://127.0.0.1:" + dashboardPort + "\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String resp = readHttpHeaders(s.getInputStream());
            assertTrue(resp.contains("101"), resp);
        } finally {
            server.stop();
        }
    }

    @Test
    void protectedBroadcastsRequireAuthentication() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        cfg.control.authToken = "secret";
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();

        try (Socket s = connectWs(port)) {
            s.setSoTimeout(5000);
            readServerFrame(s.getInputStream()); // welcome

            JsonObject data = new JsonObject();
            data.addProperty("userCode", "ABCD-EFGH");
            server.broadcastEvent("auth.prompt", data);
            s.setSoTimeout(700);
            assertThrows(SocketTimeoutException.class, () -> readServerFrame(s.getInputStream()));

            s.setSoTimeout(5000);
            sendMasked(s.getOutputStream(), "{\"id\":\"1\",\"cmd\":\"hello\",\"args\":{\"token\":\"secret\"}}");
            assertTrue(readUntilId(s.getInputStream(), "\"id\":\"1\"").contains("\"authed\":true"));
            server.broadcastEvent("auth.prompt", data);
            String ev = readServerFrame(s.getInputStream());
            assertTrue(ev.contains("\"event\":\"auth.prompt\""), ev);
        } finally {
            server.stop();
        }
    }

    @Test
    void readOnlyTokenCannotRunMutatingCommands() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        cfg.control.authToken = "full";
        cfg.control.readOnlyAuthToken = "read";
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();

        try (Socket s = connectWs(port)) {
            s.setSoTimeout(5000);
            readServerFrame(s.getInputStream()); // welcome
            sendMasked(s.getOutputStream(), "{\"id\":\"1\",\"cmd\":\"hello\",\"args\":{\"token\":\"read\"}}");
            String hello = readUntilId(s.getInputStream(), "\"id\":\"1\"");
            assertTrue(hello.contains("\"scope\":\"read\""), hello);

            sendMasked(s.getOutputStream(), "{\"id\":\"2\",\"cmd\":\"ping\",\"args\":{}}");
            assertTrue(readUntilId(s.getInputStream(), "\"id\":\"2\"").contains("\"ok\":true"));

            sendMasked(s.getOutputStream(), "{\"id\":\"3\",\"cmd\":\"move\",\"args\":{\"forward\":true}}");
            String denied = readUntilId(s.getInputStream(), "\"id\":\"3\"");
            assertTrue(denied.contains("\"ok\":false"), denied);
            assertTrue(denied.contains("\"UNAUTHORIZED\""), denied);
        } finally {
            server.stop();
        }
    }

    @Test
    void nonObjectArgsReturnBadArgs() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        cfg.control.authToken = "";
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();
        try (Socket s = connectWs(port)) {
            s.setSoTimeout(5000);
            sendMasked(s.getOutputStream(), "{\"id\":\"1\",\"cmd\":\"ping\",\"args\":[]}");
            String resp = readUntilId(s.getInputStream(), "\"id\":\"1\"");
            assertTrue(resp.contains("\"BAD_ARGS\""), resp);
        } finally {
            server.stop();
        }
    }

    @Test
    void commandRateLimitIsPerConnection() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ClefConfig cfg = new ClefConfig();
        cfg.control.host = "127.0.0.1";
        cfg.control.port = port;
        cfg.control.authToken = "";
        cfg.control.rateLimitPerSecond = 0.01;
        cfg.control.rateLimitBurst = 1;
        ControlServer server = new ControlServer(cfg,
                new ClefServices(new ScreenshotService(cfg), new BaritoneNavigator(), new InputController(), new ActionManager(), new UseController()));
        server.start();
        try (Socket s = connectWs(port)) {
            s.setSoTimeout(5000);
            sendMasked(s.getOutputStream(), "{\"id\":\"1\",\"cmd\":\"ping\",\"args\":{}}");
            assertTrue(readUntilId(s.getInputStream(), "\"id\":\"1\"").contains("\"ok\":true"));
            sendMasked(s.getOutputStream(), "{\"id\":\"2\",\"cmd\":\"ping\",\"args\":{}}");
            String limited = readUntilId(s.getInputStream(), "\"id\":\"2\"");
            assertTrue(limited.contains("rate limit exceeded"), limited);
        } finally {
            server.stop();
        }
    }

    // ---- minimal WS client helpers --------------------------------------------------

    private static Socket connectWs(int port) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        String key = Base64.getEncoder().encodeToString("clef-test-keybyte".substring(0, 16)
                .getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().write(("GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        readHttpHeaders(s.getInputStream());
        return s;
    }

    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            byte[] a = buf.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void sendMasked(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {0x21, 0x22, 0x23, 0x24};
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x81);
        f.write(0x80 | payload.length); // assumes <126 (our test messages are small)
        f.write(mask);
        for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Reads server text frames until one containing {@code needle} (skips e.g. the welcome event). */
    private static String readUntilId(InputStream in, String needle) throws Exception {
        for (int i = 0; i < 10; i++) {
            String frame = readServerFrame(in);
            if (frame.contains(needle)) return frame;
        }
        throw new AssertionError("did not receive frame containing " + needle);
    }

    private static String readServerFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 == -1 || b1 == -1) throw new AssertionError("server closed");
        int len = b1 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        }
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(data, off, len - off);
            if (r == -1) throw new AssertionError("EOF mid-frame");
            off += r;
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
