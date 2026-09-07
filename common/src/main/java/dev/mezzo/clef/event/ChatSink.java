package dev.mezzo.clef.event;

/**
 * Version-neutral hand-off for received chat lines.
 *
 * <p>Minecraft 1.19.3 added Fabric API's {@code fabric-message-api-v1}
 * ({@code ClientReceiveMessageEvents}), and every target from 1.19.4 up registers against it
 * directly. Older releases have no such event, so those targets read the chat packets themselves
 * with a {@code ClientPacketListener} mixin and push the result through here.
 *
 * <p>This class deliberately holds no Minecraft types: it is the seam between a version's mixin
 * and the shared control plane, so the {@code chat} event on the wire carries the same fields
 * regardless of which mechanism produced it. Newer targets simply never call it.
 *
 * <p>Threading: {@link #emit} is called from the client thread (the packet mixins inject at TAIL,
 * which vanilla only reaches after it has rescheduled handling onto the main thread). The listener
 * field is {@code volatile} so registration during mod init is visible to it.
 */
public final class ChatSink {

    /** Receives one chat line. {@code sender} is null for non-player (system/game) messages. */
    @FunctionalInterface
    public interface Listener {
        void onChat(String text, String sender, String kind, boolean overlay);
    }

    private static volatile Listener listener;

    private ChatSink() {
    }

    /** Registers the (single) consumer — the control plane's chat event emitter. */
    public static void setListener(Listener l) {
        listener = l;
    }

    /**
     * Publishes a chat line. Safe to call before anything registers, and never throws into the
     * packet handler: a listener that blows up must not desync the connection.
     *
     * @param kind    {@code "chat"} for player messages, {@code "game"} for system messages
     * @param overlay true when the server asked for the action-bar slot rather than the chat log
     */
    public static void emit(String text, String sender, String kind, boolean overlay) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onChat(text, sender, kind, overlay);
        } catch (Throwable ignored) {
            // A broken subscriber must not break packet handling.
        }
    }
}
