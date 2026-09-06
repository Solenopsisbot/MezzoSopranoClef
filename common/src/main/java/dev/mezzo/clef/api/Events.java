package dev.mezzo.clef.api;

import com.google.gson.JsonObject;

/**
 * A tiny static bridge from "anywhere in the client" to the control plane's event stream.
 *
 * <p>Most events are produced by {@code ClefClient}, which holds the {@link ControlServer} directly.
 * Packet-sourced events can't: they're raised from mixins on {@code ClientPlayNetworkHandler}, which
 * have no path to the mod's object graph. This class is the seam — {@code ClefClient} binds the
 * running server once at init, mixins call {@link #wants} and {@link #emit}.</p>
 *
 * <p>{@link #wants} is the important half. Every producer must gate on it, so a bot with nobody
 * subscribed to {@code blockUpdate} does not pay to build a JSON object for every block the server
 * changes anywhere in render distance.</p>
 */
public final class Events {

    private static volatile ControlServer server;

    /** Called once by {@code ClefClient} after the control plane is constructed. */
    public static void bind(ControlServer controlServer) {
        server = controlServer;
    }

    /** Called on shutdown so a stopped server isn't kept alive by a static field. */
    public static void unbind() {
        server = null;
    }

    /** True if at least one connection asked for {@code event}. Always check before building a payload. */
    public static boolean wants(String event) {
        ControlServer s = server;
        return s != null && s.hasSubscribers(event);
    }

    /** Delivers to subscribers. Cheap no-op if nothing is bound. */
    public static void emit(String event, JsonObject data) {
        ControlServer s = server;
        if (s != null) s.emitEvent(event, data);
    }

    private Events() {}
}
