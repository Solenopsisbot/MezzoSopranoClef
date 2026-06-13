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
    private final boolean requireAuth;
    private DashboardServer dashboard;

    /** Shared service handles (screenshots, navigation) reachable from any command. */
    public final ClefServices services;

    public ControlServer(ClefConfig config, ClefServices services) {
        this.config = config;
        this.services = services;
        this.requireAuth = config.control.authToken != null && !config.control.authToken.isBlank();
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

    // ---- protocol -------------------------------------------------------------------

    @Override
    public void onOpen(WsConnection conn) {
        JsonObject data = new JsonObject();
        data.addProperty("server", "MezzoSopranoClef");
        data.addProperty("requiresAuth", requireAuth);
        sendEvent(conn, "welcome", data);
    }

    @Override
    public void onMessage(WsConnection conn, String message) {
        JsonObject req;
        try {
            req = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            sendError(conn, null, "invalid JSON request");
            return;
        }

        String id = req.has("id") && !req.get("id").isJsonNull() ? req.get("id").getAsString() : null;
        String cmd = req.has("cmd") && !req.get("cmd").isJsonNull() ? req.get("cmd").getAsString() : null;
        JsonObject args = req.has("args") && req.get("args").isJsonObject()
                ? req.getAsJsonObject("args") : new JsonObject();

        if (cmd == null) {
            sendError(conn, id, "missing 'cmd'");
            return;
        }

        if (cmd.equals("hello")) {
            boolean ok = !requireAuth
                    || tokenEquals(config.control.authToken, args.has("token") ? args.get("token").getAsString() : "");
            conn.attributes.put("authed", ok);
            if (ok) {
                JsonObject r = new JsonObject();
                r.addProperty("authed", true);
                sendResult(conn, id, r);
            } else {
                sendError(conn, id, "bad token");
            }
            return;
        }

        if (requireAuth && !Boolean.TRUE.equals(conn.attributes.get("authed"))) {
            sendError(conn, id, "unauthorized — send {cmd:'hello',args:{token:'...'}} first");
            return;
        }

        try {
            CommandContext ctx = new CommandContext(this, conn, args);
            JsonElement result = dispatcher.dispatch(cmd, ctx);
            sendResult(conn, id, result == null ? JsonNull.INSTANCE : result);
        } catch (Exception e) {
            MezzoClef.LOG.debug("command '{}' failed", cmd, e);
            sendError(conn, id, e.getMessage() == null ? e.toString() : e.getMessage());
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

    private void sendError(WsConnection conn, String id, String error) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        o.addProperty("ok", false);
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
        return !requireAuth || Boolean.TRUE.equals(conn.attributes.get("authed"));
    }

    private static boolean tokenEquals(String expected, String actual) {
        if (expected == null) expected = "";
        if (actual == null) actual = "";
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
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
