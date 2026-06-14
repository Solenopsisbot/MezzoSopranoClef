package dev.mezzo.clef.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mezzo.clef.MezzoClef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JSON-backed configuration. Uses Minecraft's bundled Gson (no extra dependency).
 * Anything here can also be overridden by a {@code -Dmezzoclef.*} system property,
 * resolved in {@link #applySystemPropertyOverrides()}.
 */
public final class ClefConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Auth auth = new Auth();
    public Connection connection = new Connection();
    public Control control = new Control();
    public Screenshot screenshot = new Screenshot();
    public boolean headless = true;
    /** Per-loop sleep (ms) while in-world rendering is skipped — keeps idle CPU low. Higher =
     *  lower CPU but slightly more command latency. Must stay well under 50ms to keep 20 TPS. */
    public int headlessLoopSleepMs = 20;
    /**
     * Headless only. When true, the client boots with a stub GPU device and <b>never creates or
     * touches an OpenGL context</b> — zero GPU work, runs on any host with no GPU. The default
     * {@code software} screenshot backend works without GL; this is auto-disabled when
     * {@code screenshot.backend=gl} (which physically requires a real context).
     */
    public boolean noGl = true;
    /**
     * How the GLFW window is created in headless mode:
     * <ul>
     *   <li>{@code "auto"} (default) — {@code none} on a Linux host with no DISPLAY (so no
     *       {@code xvfb} is needed), otherwise {@code hidden}.</li>
     *   <li>{@code "none"} — GLFW null platform: no window, no display server, no GPU. Requires
     *       {@link #noGl}.</li>
     *   <li>{@code "hidden"} — create a real (hidden) window; needed if you want the {@code gl}
     *       screenshot backend.</li>
     * </ul>
     */
    public String windowMode = "auto";

    public static final class Auth {
        /** "offline" or "microsoft". */
        public String mode = "offline";
        /** Offline username (offline mode only). */
        public String offlineUsername = "ClefBot";
        /**
         * Azure AD application (public client) ID for the Microsoft device-code flow. Defaults to
         * the public client registered for MezzoSopranoClef; override with your own (see README
         * "Microsoft auth setup") if you'd rather not share this app's throttling/consent. This is
         * a public-client id, NOT a secret — safe to ship. (The well-known Minecraft launcher
         * client id, by contrast, is not redistributable, which is why we register our own.)
         */
        public String azureClientId = "fec2c6a8-e025-42d8-8b6c-364fb09d8acb";
        /** Where to cache refresh/MC tokens so re-auth is silent. Relative to game dir. */
        public String tokenCacheFile = "mezzoclef/auth-cache.json";
    }

    public static final class Connection {
        /** Auto-join this server once authed + in-game. Empty = stay on title screen. */
        public boolean autoConnect = false;
        public String serverHost = "localhost";
        public int serverPort = 25565;
        /** Automatically respawn when the bot dies (a corpse can't bot). */
        public boolean autoRespawn = true;
    }

    public static final class Control {
        public boolean enabled = true;
        public String host = "127.0.0.1";
        public int port = 8731;
        /**
         * Shared secret; clients must send it in the "hello" frame. A fresh config file gets a
         * random token so browser pages cannot silently drive a local bot. Empty disables auth.
         */
        public String authToken = "";
        /** Optional lower-privilege token: can read status/schema/events/screenshots but cannot drive the bot. */
        public String readOnlyAuthToken = "";
        /**
         * Comma-separated browser origins allowed to open the WebSocket, in addition to the
         * built-in dashboard origin when enabled. CLI/script clients usually send no Origin and
         * are still accepted.
         */
        public String allowedOrigins = "";
        /** Per-connection command rate limit. Set <=0 to disable. */
        public double rateLimitPerSecond = 20.0;
        /** Per-connection burst capacity for the rate limiter. */
        public int rateLimitBurst = 40;
        /** Log command audit lines (duration, scope, result code; never token values). */
        public boolean auditLog = true;
        /** Serve a built-in browser dashboard (static page that talks to the control plane). */
        public boolean dashboard = false;
        public int dashboardPort = 8732;
    }

    public static final class Screenshot {
        /**
         * "software" (default) = CPU voxel raycaster, needs NO GPU, runs anywhere, fully tested.
         * "gl" = high-fidelity offscreen render via the real client renderer (needs a GL context;
         * experimental — see README).
         */
        public String backend = "software";
        public int defaultWidth = 1280;
        public int defaultHeight = 720;
        public float defaultFov = 70.0f;
        /** Hard ceiling to avoid OOMing on absurd resolutions. */
        public int maxWidth = 3840;
        public int maxHeight = 2160;
        /** Hard cap for width*height, independent of each side's max. Default is 4K UHD. */
        public int maxPixels = 3840 * 2160;
        /** software backend: half-size of the captured cube + max ray length (blocks). Bigger =
         *  see further but each capture reads ~(2r)^3 blocks on the client thread, so keep it
         *  modest for frequent captures. */
        public int maxRayDistance = 64;
        /** software backend: hard cap on the block snapshot volume read on the client thread. */
        public int maxSnapshotBlocks = 8_000_000;
        /** software backend: max nearby entities (players/mobs/items) drawn per capture. */
        public int maxEntities = 64;
    }

    // ---- load / save ----------------------------------------------------------------

    public static ClefConfig loadOrCreate(Path path) {
        ClefConfig cfg;
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                cfg = GSON.fromJson(json, ClefConfig.class);
                if (cfg == null) cfg = new ClefConfig();
            } else {
                cfg = new ClefConfig();
                cfg.control.authToken = generateAuthToken();
                cfg.save(path);
                MezzoClef.LOG.info("Wrote default config to {}", path);
                MezzoClef.LOG.info("Generated control-plane auth token in {}", path);
            }
        } catch (Exception e) {
            MezzoClef.LOG.error("Failed to read config {}, using defaults", path, e);
            cfg = new ClefConfig();
        }
        cfg.applySystemPropertyOverrides();
        return cfg;
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MezzoClef.LOG.error("Failed to write config {}", path, e);
        }
    }

    /** -Dmezzoclef.headless / -Dmezzoclef.ws.host / -Dmezzoclef.ws.port etc. win over the file. */
    public void applySystemPropertyOverrides() {
        String hl = System.getProperty("mezzoclef.headless");
        if (hl != null) headless = Boolean.parseBoolean(hl);
        String ls = System.getProperty("mezzoclef.loopSleepMs");
        if (ls != null) try { headlessLoopSleepMs = Integer.parseInt(ls); } catch (NumberFormatException ignored) {}
        String ng = System.getProperty("mezzoclef.nogl");
        if (ng != null) noGl = Boolean.parseBoolean(ng);
        String wm = System.getProperty("mezzoclef.window");
        if (wm != null) windowMode = wm;

        String wsHost = System.getProperty("mezzoclef.ws.host");
        if (wsHost != null) control.host = wsHost;
        String wsPort = System.getProperty("mezzoclef.ws.port");
        if (wsPort != null) try { control.port = Integer.parseInt(wsPort); } catch (NumberFormatException ignored) {}
        String wsToken = System.getProperty("mezzoclef.ws.token");
        if (wsToken != null) control.authToken = wsToken;
        String wsReadToken = System.getProperty("mezzoclef.ws.readOnlyToken");
        if (wsReadToken != null) control.readOnlyAuthToken = wsReadToken;
        String wsOrigins = System.getProperty("mezzoclef.ws.allowedOrigins");
        if (wsOrigins != null) control.allowedOrigins = wsOrigins;
        String wsRate = System.getProperty("mezzoclef.ws.rateLimitPerSecond");
        if (wsRate != null) try { control.rateLimitPerSecond = Double.parseDouble(wsRate); } catch (NumberFormatException ignored) {}
        String wsBurst = System.getProperty("mezzoclef.ws.rateLimitBurst");
        if (wsBurst != null) try { control.rateLimitBurst = Integer.parseInt(wsBurst); } catch (NumberFormatException ignored) {}
        String audit = System.getProperty("mezzoclef.ws.auditLog");
        if (audit != null) control.auditLog = Boolean.parseBoolean(audit);

        String dash = System.getProperty("mezzoclef.dashboard");
        if (dash != null) control.dashboard = Boolean.parseBoolean(dash);
        String dashPort = System.getProperty("mezzoclef.dashboard.port");
        if (dashPort != null) try { control.dashboardPort = Integer.parseInt(dashPort); } catch (NumberFormatException ignored) {}

        String authMode = System.getProperty("mezzoclef.auth.mode");
        if (authMode != null) auth.mode = authMode;
        String user = System.getProperty("mezzoclef.auth.username");
        if (user != null) auth.offlineUsername = user;
        String cid = System.getProperty("mezzoclef.auth.clientId");
        if (cid != null) auth.azureClientId = cid;

        String ca = System.getProperty("mezzoclef.connect.auto");
        if (ca != null) connection.autoConnect = Boolean.parseBoolean(ca);
        String ch = System.getProperty("mezzoclef.connect.host");
        if (ch != null) connection.serverHost = ch;
        String cp = System.getProperty("mezzoclef.connect.port");
        if (cp != null) try { connection.serverPort = Integer.parseInt(cp); } catch (NumberFormatException ignored) {}

        String sb = System.getProperty("mezzoclef.screenshot.backend");
        if (sb != null) screenshot.backend = sb;
    }

    public static String generateAuthToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
