package dev.mezzo.clef.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mezzo.clef.MezzoClef;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Microsoft OAuth 2.0 <b>device code</b> flow, end to end, with no SDK:
 *
 * <pre>
 *   MSA device code  ->  poll for MSA token  ->  Xbox Live (XBL)  ->  XSTS
 *                    ->  Minecraft services login  ->  Minecraft profile
 * </pre>
 *
 * Device code is the right grant for a headless bot: the operator opens the shown URL on
 * any device, types the code, and we poll silently. Refresh tokens are cached so restarts
 * don't re-prompt.
 *
 * <p><b>You must supply your own Azure AD application (public client) id.</b> Register one at
 * the Azure portal, enable "Allow public client flows", and it will work with the
 * {@code XboxLive.signin offline_access} scope. See README.
 */
public final class MicrosoftAuth {

    private static final String SCOPE = "XboxLive.signin offline_access";

    /** Endpoint URLs — overridable so tests can drive the flow against a local mock server. */
    record Endpoints(String deviceCode, String token, String xbl, String xsts,
                     String mcLogin, String mcProfile) {
        static Endpoints production() {
            return new Endpoints(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    "https://user.auth.xboxlive.com/user/authenticate",
                    "https://xsts.auth.xboxlive.com/xsts/authorize",
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    "https://api.minecraftservices.com/minecraft/profile");
        }
    }

    private final String clientId;
    private final Endpoints ep;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MicrosoftAuth(String clientId) {
        this(clientId, Endpoints.production());
    }

    MicrosoftAuth(String clientId, Endpoints endpoints) {
        if (clientId == null || clientId.isBlank()) {
            throw new AuthException("No Azure client id configured (auth.azureClientId). "
                    + "Register a public-client Azure app and set it — see README.");
        }
        this.clientId = clientId;
        this.ep = endpoints;
    }

    // ---- public API ----------------------------------------------------------------

    public record MsaTokens(String accessToken, String refreshToken, Instant expiresAt) {}

    public record DevicePrompt(String deviceCode, String userCode, String verificationUri,
                               String message, int intervalSeconds, Instant expiresAt) {}

    public record AuthResult(MinecraftSession session, MsaTokens tokens) {}

    /**
     * Full interactive login. Calls {@code onPrompt} with the user code + URL to display,
     * blocks polling until the user finishes (or it times out), then exchanges all the way
     * down to a Minecraft session.
     */
    public AuthResult login(Consumer<DevicePrompt> onPrompt) {
        DevicePrompt prompt = startDeviceCode();
        onPrompt.accept(prompt);
        MsaTokens msa = pollForTokens(prompt);
        return finishFromMsa(msa);
    }

    /** Silent re-login using a cached refresh token. */
    public AuthResult loginWithRefreshToken(String refreshToken) {
        MsaTokens msa = refresh(refreshToken);
        return finishFromMsa(msa);
    }

    // ---- step 1: device code --------------------------------------------------------

    public DevicePrompt startDeviceCode() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", SCOPE);
        JsonObject json = postForm(ep.deviceCode(), form, null);
        int interval = json.has("interval") ? json.get("interval").getAsInt() : 5;
        int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 900;
        return new DevicePrompt(
                json.get("device_code").getAsString(),
                json.get("user_code").getAsString(),
                json.get("verification_uri").getAsString(),
                json.has("message") ? json.get("message").getAsString() : null,
                interval,
                Instant.now().plusSeconds(expiresIn));
    }

    // ---- step 2: poll for MSA token -------------------------------------------------

    public MsaTokens pollForTokens(DevicePrompt prompt) {
        int interval = Math.max(1, prompt.intervalSeconds());
        while (Instant.now().isBefore(prompt.expiresAt())) {
            sleep(interval);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("client_id", clientId);
            form.put("device_code", prompt.deviceCode());
            JsonObject json = postFormAllowError(ep.token(), form);
            if (json.has("error")) {
                String err = json.get("error").getAsString();
                switch (err) {
                    case "authorization_pending" -> { /* keep waiting */ }
                    case "slow_down" -> interval += 5;
                    case "expired_token", "authorization_declined", "bad_verification_code" ->
                            throw new AuthException("Device code login failed: " + err);
                    default -> throw new AuthException("Device code login error: " + err);
                }
            } else {
                return toMsaTokens(json);
            }
        }
        throw new AuthException("Timed out waiting for Microsoft device-code authorization.");
    }

    public MsaTokens refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", refreshToken);
        form.put("scope", SCOPE);
        return toMsaTokens(postForm(ep.token(), form, null));
    }

    private MsaTokens toMsaTokens(JsonObject json) {
        int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
        String refresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
        return new MsaTokens(
                json.get("access_token").getAsString(),
                refresh,
                Instant.now().plusSeconds(expiresIn));
    }

    // ---- steps 3-6: Xbox -> XSTS -> Minecraft -> profile ----------------------------

    private AuthResult finishFromMsa(MsaTokens msa) {
        XboxToken xbl = xblAuthenticate(msa.accessToken());
        XboxToken xsts = xstsAuthorize(xbl.token());
        String mcToken = minecraftLogin(xsts.userHash(), xsts.token());
        Profile profile = fetchProfile(mcToken);
        MinecraftSession session = new MinecraftSession(
                profile.name(), profile.uuid(), mcToken, xsts.userHash(), MinecraftSession.Type.MSA);
        MezzoClef.LOG.info("Microsoft auth OK: {} ({})", profile.name(), profile.uuid());
        return new AuthResult(session, msa);
    }

    private record XboxToken(String token, String userHash) {}

    private XboxToken xblAuthenticate(String msaAccessToken) {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaAccessToken);
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        JsonObject json = postJson(ep.xbl(), body.toString());
        return new XboxToken(json.get("Token").getAsString(), extractUserHash(json));
    }

    private XboxToken xstsAuthorize(String xblToken) {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        props.add("UserTokens", tokens);
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");

        HttpResponse<String> resp = send(jsonRequest(ep.xsts(), body.toString()));
        if (resp.statusCode() == 401) {
            // XErr codes: 2148916233 = no Xbox account, 2148916238 = under-18/region, etc.
            JsonObject err = JsonParser.parseString(resp.body()).getAsJsonObject();
            long xerr = err.has("XErr") ? err.get("XErr").getAsLong() : 0;
            throw new AuthException("XSTS denied (XErr=" + xerr + "). "
                    + "This Microsoft account may not own Minecraft or lacks an Xbox profile.");
        }
        JsonObject json = parseOk(resp, "XSTS");
        return new XboxToken(json.get("Token").getAsString(), extractUserHash(json));
    }

    private String minecraftLogin(String userHash, String xstsToken) {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject json = postJson(ep.mcLogin(), body.toString());
        return json.get("access_token").getAsString();
    }

    private record Profile(UUID uuid, String name) {}

    private Profile fetchProfile(String mcAccessToken) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(ep.mcProfile()))
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 404) {
            throw new AuthException("Account has no Minecraft profile (does it own the game?).");
        }
        JsonObject json = parseOk(resp, "profile");
        return new Profile(undashUuid(json.get("id").getAsString()), json.get("name").getAsString());
    }

    private static String extractUserHash(JsonObject xboxResponse) {
        return xboxResponse.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
    }

    /** Minecraft returns dashless UUIDs; turn them back into a real UUID. */
    public static UUID undashUuid(String id) {
        if (id.contains("-")) return UUID.fromString(id);
        String dashed = id.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }

    // ---- tiny HTTP helpers ----------------------------------------------------------

    private JsonObject postForm(String url, Map<String, String> form, String bearer) {
        return parseOk(send(formRequest(url, form, bearer)), url);
    }

    /** Like postForm but returns the body even on 4xx (for OAuth polling "error" replies). */
    private JsonObject postFormAllowError(String url, Map<String, String> form) {
        HttpResponse<String> resp = send(formRequest(url, form, null));
        try {
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new AuthException("Bad JSON from " + url + " (HTTP " + resp.statusCode() + ")");
        }
    }

    private JsonObject postJson(String url, String body) {
        return parseOk(send(jsonRequest(url, body)), url);
    }

    private HttpRequest formRequest(String url, Map<String, String> form, String bearer) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b.build();
    }

    private HttpRequest jsonRequest(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AuthException("HTTP call to " + req.uri() + " failed: " + e.getMessage(), e);
        }
    }

    private JsonObject parseOk(HttpResponse<String> resp, String what) {
        if (resp.statusCode() / 100 != 2) {
            throw new AuthException(what + " request failed: HTTP " + resp.statusCode()
                    + " — " + truncate(resp.body()));
        }
        try {
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new AuthException("Unexpected non-JSON response from " + what);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Auth polling interrupted");
        }
    }

    /** Unchecked so call sites stay clean; the control plane reports the message. */
    public static final class AuthException extends RuntimeException {
        public AuthException(String message) { super(message); }
        public AuthException(String message, Throwable cause) { super(message, cause); }
    }
}
