package dev.mezzo.clef.replay;

import com.google.gson.JsonObject;

/**
 * One entry in a replay's {@code markers.json} — a named point on the timeline that ReplayMod's
 * timeline bar shows as a pip, and that its camera can jump to.
 *
 * <p>Field names here are not ours to choose: they are read back by ReplayStudio's
 * {@code com.replaymod.replaystudio.data.Marker}, which Gson deserializes field-by-field. Renaming
 * any of them produces a file that still opens but whose markers silently vanish.</p>
 *
 * @param name  what the marker is called; null is legal (ReplayMod shows an unnamed pip)
 * @param time  milliseconds from the start of the recording
 * @param x     marker camera position, world coordinates
 * @param yaw   marker camera rotation, degrees
 * @param roll  ReplayMod's camera has roll; the bot has no notion of it, so this is always 0
 */
public record ReplayMarker(String name, int time, double x, double y, double z,
                           float yaw, float pitch, float roll) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (name != null) o.addProperty("name", name);
        o.addProperty("time", time);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        o.addProperty("roll", roll);
        return o;
    }
}
