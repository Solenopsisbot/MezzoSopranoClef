package dev.mezzo.clef.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A bounded ring of recent chat lines, so a control client that connects late (or reconnects) can
 * still read what was said. The event stream only reaches whoever was listening at the time; this
 * is the catch-up.
 *
 * <p>Deliberately small and lossy — it holds the last {@code capacity} lines and nothing else. It is
 * not a transcript and it does not survive a restart.</p>
 *
 * <p>Written on the client thread, read from control-plane threads, so access is synchronized.</p>
 */
public final class ChatLog {

    /**
     * @param kind one of {@code chat | system | whisper | team | actionbar}
     * @param time wall-clock millis when the line arrived
     */
    public record Line(long time, String kind, String sender, String text) {

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("time", time);
            o.addProperty("kind", kind);
            if (sender != null) o.addProperty("sender", sender);
            o.addProperty("text", text);
            return o;
        }
    }

    private final Deque<Line> lines = new ArrayDeque<>();
    private final int capacity;

    public ChatLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(String kind, String sender, String text) {
        if (lines.size() >= capacity) lines.removeFirst();
        lines.addLast(new Line(System.currentTimeMillis(), kind, sender, text));
    }

    /** The most recent {@code limit} lines, oldest first. */
    public synchronized JsonArray recent(int limit) {
        int want = Math.min(Math.max(1, limit), lines.size());
        JsonArray out = new JsonArray();
        int skip = lines.size() - want;
        int i = 0;
        for (Line line : lines) {
            if (i++ < skip) continue;
            out.add(line.toJson());
        }
        return out;
    }

    public synchronized int size() {
        return lines.size();
    }

    public synchronized void clear() {
        lines.clear();
    }
}
