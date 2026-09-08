package dev.mezzo.clef.api.commands;

import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.api.ErrorCode;
import dev.mezzo.clef.replay.ReplayMarker;
import dev.mezzo.clef.replay.ReplayRecorder;
import java.io.IOException;

/**
 * Control-plane surface for ReplayMod-format recording.
 *
 * <h2>Why there is no "start now"</h2>
 * A replay is played back by feeding its packets into a client that begins in the login state, so
 * it has to start at login success — everything a viewer needs about the world (registries, chunks,
 * entities, the player list) arrives once, right after that, and is never sent again.
 * {@code replay.record} therefore <i>arms</i> capture, and the next connection is recorded whole.
 * It cannot retrofit a recording onto a session already in progress, and says so rather than
 * writing a file that will not open. ReplayMod itself lives under the same constraint.
 *
 * <p>{@code replay.save} is the one that works at any moment: it seals everything captured so far
 * into a finished {@code .mcpr} while the bot plays on, as many times as you ask. That is how you
 * watch a run that has not ended yet.</p>
 *
 * <p>Version-neutral by construction — every Minecraft fact these commands need is pushed into the
 * recorder from the client thread by that target's glue, so this file is shared verbatim across the
 * matrix. On a target that has not been ported yet the commands still register and report
 * {@code supported:false}, instead of disappearing from {@code help}.</p>
 */
public final class ReplayCommands {

    public static void registerAll(CommandDispatcher d) {

        d.register("replay.status",
                "replay recording state: supported, armed, recording, size, and the netty pipeline "
                        + "the tap sits in",
                ctx -> ReplayRecorder.get().status());

        d.register("replay.list", "sealed .mcpr files on disk, newest first", ctx -> {
            JsonObject o = new JsonObject();
            o.addProperty("dir", ReplayRecorder.get().replayDir().toString());
            o.add("replays", ReplayRecorder.get().list());
            return o;
        });

        d.register("replay.record",
                "arm or disarm packet capture {enabled} — takes effect on the NEXT connection",
                ctx -> {
                    ReplayRecorder recorder = ReplayRecorder.get();
                    boolean want = ctx.bool("enabled", true);
                    boolean ended = recorder.setArmed(want);
                    JsonObject o = new JsonObject();
                    o.addProperty("supported", recorder.isSupported());
                    o.addProperty("armed", recorder.isArmed());
                    o.addProperty("recording", recorder.isRecording());
                    o.addProperty("ended", ended);
                    if (want && !recorder.isSupported()) {
                        o.addProperty("note", "this build cannot record — no replay tap on this "
                                + "Minecraft target yet");
                    } else if (want && !recorder.isRecording()) {
                        // Arming mid-session is legal but does nothing until a reconnect; saying so
                        // beats letting the caller wonder why replay.save keeps failing.
                        o.addProperty("note", "armed — a recording begins at the next connection's "
                                + "login, so reconnect if you are already in a world");
                    }
                    return o;
                });

        d.register("replay.save",
                "seal everything recorded so far into a playable .mcpr {name?} — recording continues",
                ctx -> {
                    ReplayRecorder recorder = ReplayRecorder.get();
                    try {
                        JsonObject o = recorder.save(ctx.str("name", null)).toJson();
                        o.addProperty("saved", true);
                        return o;
                    } catch (IllegalStateException e) {
                        throw notRecording(recorder);
                    } catch (IOException e) {
                        throw new ApiException(ErrorCode.COMMAND_FAILED,
                                "could not write the replay: " + e.getMessage());
                    }
                });

        d.register("replay.marker",
                "drop a named marker on the replay timeline at the bot's position {name?}",
                ctx -> {
                    ReplayRecorder recorder = ReplayRecorder.get();
                    try {
                        ReplayMarker marker = recorder.marker(ctx.str("name", null));
                        JsonObject o = new JsonObject();
                        o.addProperty("added", true);
                        o.add("marker", marker.toJson());
                        return o;
                    } catch (IllegalStateException e) {
                        throw notRecording(recorder);
                    }
                });
    }

    /**
     * One error for "there is nothing to record into", with the reason it is nothing — unsupported,
     * armed-but-late, and never-armed are three different mistakes and only one of them is fixable
     * by reconnecting.
     */
    private static ApiException notRecording(ReplayRecorder recorder) {
        if (!recorder.isSupported()) {
            return new ApiException(ErrorCode.COMMAND_FAILED,
                    "replay recording is not available on this Minecraft target");
        }
        return new ApiException(ErrorCode.NOT_CONNECTED, recorder.isArmed()
                ? "no recording in progress — capture is armed, but a replay can only begin at a "
                        + "connection's login; reconnect to start one"
                : "no recording in progress — run replay.record {enabled:true}, then connect");
    }

    private ReplayCommands() {}
}
