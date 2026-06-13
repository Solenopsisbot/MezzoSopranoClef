package dev.mezzo.clef.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the entire Microsoft device-code flow (and refresh) against a local mock of the
 * MSA / Xbox Live / Minecraft endpoints — so the auth chain is actually tested end to end
 * without contacting real Microsoft servers.
 */
class MicrosoftAuthFlowTest {

    private HttpServer server;
    private MicrosoftAuth.Endpoints endpoints;
    private final AtomicInteger tokenPolls = new AtomicInteger();

    @BeforeEach
    void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));

        server.createContext("/devicecode", ex -> respond(ex, 200, """
                {"device_code":"DEV","user_code":"ABCD-EFGH",
                 "verification_uri":"https://microsoft.com/link","expires_in":900,"interval":0,
                 "message":"go to the link"}"""));

        server.createContext("/token", ex -> {
            String body = readBody(ex);
            if (body.contains("grant_type=refresh_token")) {
                respond(ex, 200, """
                        {"access_token":"MSA_AT2","refresh_token":"MSA_RT2","expires_in":3600}""");
            } else if (tokenPolls.getAndIncrement() == 0) {
                respond(ex, 400, "{\"error\":\"authorization_pending\"}"); // first poll: still pending
            } else {
                respond(ex, 200, """
                        {"access_token":"MSA_AT","refresh_token":"MSA_RT","expires_in":3600}""");
            }
        });

        server.createContext("/xbl", ex -> respond(ex, 200, """
                {"Token":"XBL_TOKEN","DisplayClaims":{"xui":[{"uhs":"USERHASH"}]}}"""));
        server.createContext("/xsts", ex -> respond(ex, 200, """
                {"Token":"XSTS_TOKEN","DisplayClaims":{"xui":[{"uhs":"USERHASH"}]}}"""));
        server.createContext("/mclogin", ex -> respond(ex, 200, """
                {"access_token":"MC_TOKEN","expires_in":86400}"""));
        server.createContext("/profile", ex -> respond(ex, 200, """
                {"id":"069a79f444e94726a5befca90e38aaf5","name":"TestBot"}"""));

        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        endpoints = new MicrosoftAuth.Endpoints(
                base + "/devicecode", base + "/token", base + "/xbl",
                base + "/xsts", base + "/mclogin", base + "/profile");
    }

    @AfterEach
    void stopMockServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void fullDeviceCodeFlowProducesSession() {
        MicrosoftAuth msa = new MicrosoftAuth("test-client-id", endpoints);
        AtomicReference<MicrosoftAuth.DevicePrompt> prompt = new AtomicReference<>();

        MicrosoftAuth.AuthResult r = msa.login(prompt::set);

        assertNotNull(prompt.get());
        assertEquals("ABCD-EFGH", prompt.get().userCode());
        assertEquals("https://microsoft.com/link", prompt.get().verificationUri());

        MinecraftSession s = r.session();
        assertEquals("TestBot", s.username());
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), s.uuid());
        assertEquals("MC_TOKEN", s.accessToken());
        assertEquals("USERHASH", s.xuid());
        assertEquals(MinecraftSession.Type.MSA, s.type());
        assertTrue(s.isOnline());

        assertEquals("MSA_RT", r.tokens().refreshToken());
        assertTrue(tokenPolls.get() >= 2, "should have polled through authorization_pending");
    }

    @Test
    void refreshTokenReloginWorks() {
        MicrosoftAuth msa = new MicrosoftAuth("test-client-id", endpoints);
        MicrosoftAuth.AuthResult r = msa.loginWithRefreshToken("OLD_RT");
        assertEquals("TestBot", r.session().username());
        assertEquals("MC_TOKEN", r.session().accessToken());
        assertEquals("MSA_RT2", r.tokens().refreshToken(), "refresh should rotate the refresh token");
    }

    @Test
    void deviceCodeErrorSurfacesAsAuthException() {
        // Replace the token endpoint with a hard error.
        server.removeContext("/token");
        server.createContext("/token", ex -> respond(ex, 400, "{\"error\":\"expired_token\"}"));
        MicrosoftAuth msa = new MicrosoftAuth("test-client-id", endpoints);
        assertThrows(MicrosoftAuth.AuthException.class, () -> msa.login(p -> {}));
    }

    // ---- helpers --------------------------------------------------------------------

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.strip().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
