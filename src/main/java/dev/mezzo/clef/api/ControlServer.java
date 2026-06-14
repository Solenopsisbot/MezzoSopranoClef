package dev.mezzo.clef.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.commands.CoreCommands;
import dev.mezzo.clef.api.ws.WsConnection;
import dev.mezzo.clef.api.ws.WsServer;
import dev.mezzo.clef.config.ClefConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The control plane. Speaks a tiny JSON protocol over WebSocket:
 *
 * <pre>
 *   request  -> {"id":"42","cmd":"status","args":{...}}
 *   response -> {"id":"42","ok":true,"result":{...}}      or {"id":"42","ok":false,"error":"..."}
 *   event    -> {"event":"chat","data":{...}}             (unsolicited, broadcast to all)
 * </pre>
 *
 * If {@code control.authToken} is set, a client must first send
 * {@code {"cmd":"hello","args":{"token":"..."}}} before any other command is accepted.
 */
public final class ControlServer implements WsServer.Listener {

    private final ClefConfig config;
    private final WsServer ws;
    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private final Gson gson = new Gson();
    private DashboardServer dashboard;
    private final AtomicLong commandsHandled = new AtomicLong();
    private final AtomicLong commandsFailed = new AtomicLong();
    private final AtomicLong commandTotalNanos = new AtomicLong();

    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "ping", "help", "schema", "stats", "subscribe", "unsubscribe", "events",
            "status", "auth.status", "players", "screenshot", "nav.status",
            "inventory", "entities", "blockAt", "container", "screen", "serverui", "findItem");

    /** Shared service handles (screenshots, navigation) reachable from any command. */
    public final ClefServices services;

    public ControlServer(ClefConfig config, ClefServices services) {
        this.config = config;
        this.services = services;
        this.ws = new WsServer(config.control.host, config.control.port, this, allowedOrigins(config));
    }

    public void start() throws IOException {
        CoreCommands.registerAll(dispatcher);
        dev.mezzo.clef.api.commands.ActionCommands.registerAll(dispatcher);
        dev.mezzo.clef.api.commands.UiCommands.registerAll(dispatcher);
        ws.start();
        if (config.control.dashboard) {
            dashboard = new DashboardServer(config.control.host, config.control.dashboardPort, config.control.port);
            try {
                dashboard.start();
            } catch (IOException e) {
                MezzoClef.LOG.error("Dashboard failed to start on {}:{}",
                        config.control.host, config.control.dashboardPort, e);
                dashboard = null;
            }
        }
    }

    public void stop() {
        if (dashboard != null) dashboard.stop();
        ws.stop();
    }

    public CommandDispatcher dispatcher() {
        return dispatcher;
    }

    public int connectionCount() {
        return ws.connectionCount();
    }

    public long commandsHandled() {
        return commandsHandled.get();
    }

    public long commandsFailed() {
        return commandsFailed.get();
    }

    public long commandTotalNanos() {
        return commandTotalNanos.get();
    }

    // ---- protocol -------------------------------------------------------------------

    @Override
    public void onOpen(WsConnection conn) {
        JsonObject data = new JsonObject();
        data.addProperty("server", "MezzoSopranoClef");
        data.addProperty("protocol", ApiSchema.PROTOCOL_VERSION);
        data.addProperty("requiresAuth", requiresAuth());
        sendEvent(conn, "welcome", data);
    }

    @Override
    public void onMessage(WsConnection conn, String message) {
        JsonObject req;
        try {
            req = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            sendError(conn, null, ErrorCode.INVALID_JSON, "invalid JSON request");
            return;
        }

        String id;
        String cmd;
        try {
            id = req.has("id") && !req.get("id").isJsonNull() ? req.get("id").getAsString() : null;
            cmd = req.has("cmd") && !req.get("cmd").isJsonNull() ? req.get("cmd").getAsString() : null;
        } catch (RuntimeException e) {
            sendError(conn, null, ErrorCode.BAD_ARGS, "'id' and 'cmd' must be strings");
            return;
        }
        if (req.has("args") && !req.get("args").isJsonNull() && !req.get("args").isJsonObject()) {
            sendError(conn, id, ErrorCode.BAD_ARGS, "'args' must be an object");
            return;
        }
        JsonObject args = req.has("args") && req.get("args").isJsonObject()
                ? req.getAsJsonObject("args") : new JsonObject();

        if (cmd == null) {
            sendError(conn, id, ErrorCode.MISSING_CMD, "missing 'cmd'");
            return;
        }

        if (cmd.equals("hello")) {
            String token;
            try {
                if (args.has("token") && !args.get("token").isJsonNull()
                        && (!args.get("token").isJsonPrimitive() || !args.getAsJsonPrimitive("token").isString())) {
                    throw new IllegalArgumentException("token must be string");
                }
                token = args.has("token") && !args.get("token").isJsonNull()
                        ? args.get("token").getAsString() : "";
            } catch (RuntimeException e) {
                sendError(conn, id, ErrorCode.BAD_ARGS, "arg 'token' must be string");
                return;
            }
            String scope = tokenScope(token);
            boolean ok = !requiresAuth() || scope != null;
            conn.attributes.put("authed", ok);
            if (ok) {
                conn.attributes.put("scope", scope == null ? "full" : scope);
                JsonObject r = new JsonObject();
                r.addProperty("authed", true);
                r.addProperty("scope", scope == null ? "full" : scope);
                r.addProperty("protocol", ApiSchema.PROTOCOL_VERSION);
                sendResult(conn, id, r);
            } else {
                sendError(conn, id, ErrorCode.BAD_TOKEN, "bad token");
            }
            return;
        }

        if (requiresAuth() && !Boolean.TRUE.equals(conn.attributes.get("authed"))) {
            sendError(conn, id, ErrorCode.UNAUTHORIZED, "unauthorized — send {cmd:'hello',args:{token:'...'}} first");
            return;
        }
        if (!allowByScope(conn, cmd)) {
            sendError(conn, id, ErrorCode.UNAUTHORIZED, "read-only token cannot run command: " + cmd);
            return;
        }
        if (!rateAllowed(conn)) {
            sendError(conn, id, ErrorCode.RATE_LIMIT, "rate limit exceeded");
            return;
        }

        long start = System.nanoTime();
        ErrorCode auditCode = null;
        try {
            ApiSchema.validateArgs(cmd, args);
            CommandContext ctx = new CommandContext(this, conn, args);
            JsonElement result = dispatcher.dispatch(cmd, ctx);
            sendResult(conn, id, result == null ? JsonNull.INSTANCE : result);
            auditCode = null;
        } catch (Exception e) {
            MezzoClef.LOG.debug("command '{}' failed", cmd, e);
            // Recover a machine-readable code from anywhere in the cause chain (onMain re-wraps).
            ApiException coded = ApiException.find(e);
            ErrorCode code = coded != null ? coded.code : ErrorCode.COMMAND_FAILED;
            String msg = coded != null ? coded.getMessage()
                    : (e.getMessage() == null ? e.toString() : e.getMessage());
            sendError(conn, id, code, msg);
            auditCode = code;
        } finally {
            long elapsed = System.nanoTime() - start;
            commandsHandled.incrementAndGet();
            commandTotalNanos.addAndGet(elapsed);
            if (auditCode != null) commandsFailed.incrementAndGet();
            audit(conn, cmd, auditCode, elapsed);
        }
    }

    @Override
    public void onClose(WsConnection conn) {
        // nothing to clean up per-connection yet
    }

    // ---- outbound -------------------------------------------------------------------

    /** Broadcasts protected lifecycle events to authenticated connections only. */
    public void broadcastEvent(String event, JsonElement data) {
        String frame = envelopeEvent(event, data);
        ws.forEach(conn -> {
            if (isAuthed(conn)) {
                try {
                    conn.sendText(frame);
                } catch (IOException ignored) {
                    // peer went away mid-broadcast; its reader thread will clean up
                }
            }
        });
    }

    /** True if any connection is subscribed to {@code event} (or to all via "*"). Lets event
     *  producers skip expensive work when nobody is listening. */
    public boolean hasSubscribers(String event) {
        boolean[] found = {false};
        ws.forEach(conn -> {
            Object subs = conn.attributes.get("subs");
            if (subs instanceof java.util.Set<?> set && (set.contains(event) || set.contains("*"))) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** Delivers an event only to connections subscribed to it (via the {@code subscribe} command). */
    public void emitEvent(String event, JsonElement data) {
        String frame = envelopeEvent(event, data);
        ws.forEach(conn -> {
            Object subs = conn.attributes.get("subs");
            if (isAuthed(conn) && subs instanceof java.util.Set<?> set && (set.contains(event) || set.contains("*"))) {
                try {
                    conn.sendText(frame);
                } catch (java.io.IOException ignored) {
                    // peer went away mid-broadcast; its reader thread will clean up
                }
            }
        });
    }

    public void sendEvent(WsConnection conn, String event, JsonElement data) {
        try {
            conn.sendText(envelopeEvent(event, data));
        } catch (IOException ignored) {
        }
    }

    private void sendResult(WsConnection conn, String id, JsonElement result) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        o.addProperty("ok", true);
        o.add("result", result);
        send(conn, o);
    }

    private void sendError(WsConnection conn, String id, ErrorCode code, String error) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        o.addProperty("ok", false);
        o.addProperty("code", code.name());
        o.addProperty("error", error);
        send(conn, o);
    }

    private String envelopeEvent(String event, JsonElement data) {
        JsonObject o = new JsonObject();
        o.addProperty("event", event);
        o.add("data", data == null ? JsonNull.INSTANCE : data);
        return gson.toJson(o);
    }

    private void send(WsConnection conn, JsonObject o) {
        try {
            conn.sendText(gson.toJson(o));
        } catch (IOException ignored) {
        }
    }

    private boolean isAuthed(WsConnection conn) {
        return !requiresAuth() || Boolean.TRUE.equals(conn.attributes.get("authed"));
    }

    private boolean allowByScope(WsConnection conn, String cmd) {
        Object scope = conn.attributes.get("scope");
        return !"read".equals(scope) || READ_ONLY_COMMANDS.contains(cmd);
    }

    private boolean rateAllowed(WsConnection conn) {
        if (config.control.rateLimitPerSecond <= 0 || config.control.rateLimitBurst <= 0) return true;
        TokenBucket bucket = (TokenBucket) conn.attributes.computeIfAbsent("rateBucket",
                ignored -> new TokenBucket(config.control.rateLimitPerSecond, config.control.rateLimitBurst));
        return bucket.take();
    }

    private void audit(WsConnection conn, String cmd, ErrorCode code, long elapsedNanos) {
        if (!config.control.auditLog) return;
        String result = code == null ? "OK" : code.name();
        MezzoClef.LOG.info("control command conn={} scope={} cmd={} result={} durationMs={}",
                conn.id(), conn.attributes.getOrDefault("scope", requiresAuth() ? "unauthenticated" : "full"),
                cmd, result, elapsedNanos / 1_000_000.0);
    }

    private String tokenScope(String actual) {
        if (!requiresAuth()) return "full";
        if (hasToken(config.control.authToken) && tokenEquals(config.control.authToken, actual)) return "full";
        if (hasToken(config.control.readOnlyAuthToken) && tokenEquals(config.control.readOnlyAuthToken, actual)) return "read";
        return null;
    }

    private boolean requiresAuth() {
        return hasToken(config.control.authToken) || hasToken(config.control.readOnlyAuthToken);
    }

    private static boolean hasToken(String token) {
        return token != null && !token.isBlank();
    }

    private static boolean tokenEquals(String expected, String actual) {
        if (expected == null) expected = "";
        if (actual == null) actual = "";
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TokenBucket {
        private final double refillPerSecond;
        private final double burst;
        private double tokens;
        private long lastNanos = System.nanoTime();

        TokenBucket(double refillPerSecond, int burst) {
            this.refillPerSecond = refillPerSecond;
            this.burst = burst;
            this.tokens = burst;
        }

        synchronized boolean take() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            tokens = Math.min(burst, tokens + elapsedSeconds * refillPerSecond);
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }

    private static Set<String> allowedOrigins(ClefConfig config) {
        Set<String> origins = new LinkedHashSet<>();
        if (config.control.allowedOrigins != null && !config.control.allowedOrigins.isBlank()) {
            for (String raw : config.control.allowedOrigins.split(",")) {
                String origin = raw.trim();
                if (!origin.isEmpty()) origins.add(origin);
            }
        }
        if (config.control.dashboard) {
            addOriginAliases(origins, config.control.host, config.control.dashboardPort);
        }
        return origins;
    }

    private static void addOriginAliases(Set<String> origins, String host, int port) {
        String h = host == null || host.isBlank() || host.equals("0.0.0.0") ? "127.0.0.1" : host;
        origins.add("http://" + h + ":" + port);
        if (h.equals("127.0.0.1") || h.equals("localhost")) {
            origins.add("http://127.0.0.1:" + port);
            origins.add("http://localhost:" + port);
        }
    }
}
