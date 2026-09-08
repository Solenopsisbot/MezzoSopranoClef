package dev.mezzo.clef.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.Events;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.platform.Platform;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-client packet recording, in ReplayMod's own {@code .mcpr} format.
 *
 * <p>The bot does not play replays back — it has no renderer worth the name and no human looking
 * at it. It <i>produces</i> them, so that a real ReplayMod client (or ReplayStudio, or anything
 * else that reads the format) can. Drop a sealed file into {@code replay_recordings/} on a normal
 * install and it appears in the replay list like any other.
 *
 * <h2>What "record each client" means here</h2>
 * Recording is a property of a <i>connection</i>, not of a command. A replay is only playable if it
 * starts at login, because everything the viewer needs — the world, the entities, the registries —
 * arrives once, right after login, and is never repeated. So there is no "start recording now"
 * mid-session: {@code replay.record} arms capture, and the next connection is recorded from its
 * first packet. That is the same constraint ReplayMod itself lives under.
 *
 * <p>What you <i>can</i> do at any moment is {@link #save}, which seals everything captured so far
 * into a finished, openable {@code .mcpr} while the bot keeps playing — repeatedly, if you like.
 * That is the honest version of "watch it while it happens": the file is a complete replay up to
 * the instant you asked, and the next one carries on from the same recording.
 *
 * <h2>Threading</h2>
 * Packets arrive on the netty IO thread, saves come from a control-plane thread, and metadata is
 * refreshed from the client thread ({@link #refresh}). {@link McprWriter} is synchronized, the
 * session reference is volatile, and nothing in {@code common/} ever touches a Minecraft object —
 * which is why the client-thread half is a snapshot pushed in rather than a lookup pulled out.
 */
public final class ReplayRecorder implements ReplayTap.Sink {

    private static final ReplayRecorder INSTANCE = new ReplayRecorder();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    /** Written into {@code metaData.json} so a replay's origin is obvious in ReplayStudio. */
    private static final String GENERATOR = "MezzoSopranoClef";

    public static ReplayRecorder get() {
        return INSTANCE;
    }

    /** One connection's recording. Immutable except for the writer it owns. */
    private static final class Session {
        final McprWriter writer;
        /** The connection this recording is reading. Dropped with the session, so nothing is pinned. */
        final ChannelPipeline pipeline;
        final long id;
        final long startedAtEpochMs = System.currentTimeMillis();
        final long startedAtNanos = System.nanoTime();
        final List<ReplayMarker> markers = new ArrayList<>();
        /** Packets this recording added that the server never sent — see {@link #inject}. */
        final java.util.concurrent.atomic.AtomicLong injected = new java.util.concurrent.atomic.AtomicLong();
        volatile boolean ended;

        Session(McprWriter writer, ChannelPipeline pipeline, long id) {
            this.writer = writer;
            this.pipeline = pipeline;
            this.id = id;
        }

        int elapsedMs() {
            return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        }
    }

    /**
     * Whether this build can record at all. Set by the client entrypoint of a target that carries
     * the {@code Connection} mixin the tap is installed from. Everywhere else the commands still
     * register and still answer — they just say honestly that the target cannot do it yet, which
     * beats the command vanishing from {@code help} on half the matrix.
     */
    private long nextSessionId = 1;
    private volatile boolean supported;
    private volatile boolean armed;
    private volatile Session session;
    /** Where the bot is, refreshed each tick while armed. Only markers need it. */
    private volatile double[] pose = {0, 0, 0, 0, 0};
    /** Last snapshot pushed in from the client thread; what sealing writes into the metadata. */
    private volatile ReplaySessionInfo info;
    /**
     * Inbound handler order, as evidence for {@code status}. Snapshotted when the tap is installed
     * (which is what a "recording never started" diagnosis needs) and replaced by the order at
     * login success, when the codec slot has settled on its final name.
     */
    private volatile List<String> pipelineNames = List.of();
    private volatile String selfTrackState = "off";
    private volatile String selfTrackDetail;

    private ReplayRecorder() {}

    // ---- lifecycle ------------------------------------------------------------------

    /**
     * Declares that this target can record — i.e. it carries the {@code Connection} mixin that
     * installs the tap — and applies {@code replay.enabled} from the config. Called once from the
     * client entrypoint.
     */
    public void enableOnThisTarget() {
        this.supported = true;
        this.armed = config().enabled;
        MezzoClef.LOG.info("Replay recording available (armed={}, dir={})", armed, replayDir());
    }

    public boolean isSupported() {
        return supported;
    }

    /**
     * Pushes the bot's position in from the client thread. Markers carry a camera position and
     * this is the only thing that needs one, so it is a plain snapshot rather than a lookup.
     */
    public void pose(double x, double y, double z, float yaw, float pitch) {
        this.pose = new double[] {x, y, z, yaw, pitch};
    }

    /**
     * Arms or disarms capture. Turning it on affects the <b>next</b> connection: the current one
     * has already sent the login packets a replay has to begin with. Turning it off ends any
     * recording in progress (saving it first if {@code replay.autoSave}).
     *
     * @return true if a recording was in progress and has now been ended
     */
    public boolean setArmed(boolean value) {
        this.armed = value && supported;
        if (!value && session != null) {
            endSession("disarmed");
            return true;
        }
        return false;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean isRecording() {
        Session s = session;
        return s != null && !s.ended;
    }

    /**
     * Identifies the current recording, or 0 when there is none. Anything keeping per-recording
     * state of its own (the self-body track does) watches this to know when to start over.
     */
    public long sessionId() {
        Session s = session;
        return s == null || s.ended ? 0L : s.id;
    }

    /**
     * The netty pipeline of the connection being recorded, or null. Exists for one caller: the
     * per-version glue that encodes synthetic packets needs the same codec the recording is made
     * of, and that codec lives in the pipeline slot next to the tap.
     */
    public ChannelPipeline activePipeline() {
        Session s = session;
        return s == null || s.ended ? null : s.pipeline;
    }

    /**
     * Appends a packet the server never sent — the bot's own spawn, movement and equipment, which
     * a server never echoes back to the player they belong to and which are therefore missing from
     * an honest capture of the wire.
     *
     * <p>Called from the client thread while packets are also arriving on the netty thread; the
     * writer serializes them and clamps timestamps, so the file stays ordered either way. Unlike
     * {@link #record} this never ends the session on the size cap: stalling the game thread to zip
     * a replay is a worse outcome than a slightly-over-budget file, and the next real packet trips
     * the cap anyway.</p>
     *
     * @return false if there is no recording to add to
     */
    public boolean inject(byte[] packet) {
        Session s = session;
        if (s == null || s.ended) return false;
        try {
            s.writer.write(s.elapsedMs(), packet, 0, packet.length);
            s.injected.incrementAndGet();
            return true;
        } catch (IOException e) {
            abort(e);
            return false;
        }
    }

    /**
     * How the per-version self-body glue is getting on, for {@code replay.status}.
     *
     * <p>Reported rather than inferred because "the bot's body is in the replay" is otherwise only
     * checkable by opening the file in ReplayMod and looking, which no test can do. {@code state}
     * is {@code verified} once a synthetic packet has been encoded <i>and decoded back</i> with the
     * connection's own codec, {@code off} on a target with no glue, and {@code failed} with a
     * reason when encoding broke and self-tracking gave up.</p>
     */
    public void reportSelfTrack(String state, String detail) {
        this.selfTrackState = state;
        this.selfTrackDetail = detail;
    }

    /**
     * Inserts the tap into a freshly-active connection, if capture is armed.
     *
     * <p>Called from a {@code Connection.channelActive} mixin, so it runs for <i>every</i>
     * connection this client opens, pings included. The tap itself only starts recording at login
     * success, which is the filter that keeps a server-list ping from producing a replay.</p>
     */
    public void install(ChannelPipeline pipeline) {
        if (!armed) return;
        try {
            // Reported by replay.status either way: when a recording never starts, the handler
            // order is the first thing worth looking at, and "we could not find the decoder" is a
            // very different problem from "we installed but saw no login success".
            this.pipelineNames = List.copyOf(pipeline.names());
            this.selfTrackState = "pending";
            this.selfTrackDetail = null;
            String anchor = inboundCodecName(pipeline);
            // An in-memory (integrated server) pipeline has neither name: packets move as objects,
            // never as bytes, so there is nothing here to copy. Singleplayer is out of scope rather
            // than silently broken.
            if (anchor == null) {
                MezzoClef.LOG.info("Replay tap skipped: no inbound codec slot in pipeline {}",
                        pipelineNames);
                return;
            }
            if (pipeline.get(ReplayTap.NAME) != null) return;
            pipeline.addBefore(anchor, ReplayTap.NAME, new ReplayTap(this));
            this.pipelineNames = List.copyOf(pipeline.names());
            MezzoClef.LOG.info("Replay tap installed; inbound pipeline is now {}", pipelineNames);
        } catch (RuntimeException e) {
            // Losing the race against a pipeline being torn down is not worth a crash.
            MezzoClef.LOG.warn("Replay tap could not be installed: {}", e.toString());
        }
    }

    /**
     * The pipeline slot the inbound codec occupies, which is where the tap has to sit in front of.
     *
     * <p>Two names, and which one is live depends on <i>when</i> you look. From 1.20.2 the codec
     * slot is created empty as {@code inbound_config} (an {@code UnconfiguredPipelineHandler}) and
     * only becomes {@code decoder} once a protocol is bound to the connection — and binding is a
     * {@code ChannelPipeline.replace}, which keeps the handler's position, so a tap inserted before
     * {@code inbound_config} is still immediately before {@code decoder} afterwards. At
     * {@code channelActive}, which is where we install, the slot is invariably still unconfigured;
     * on 1.20.1 and older there is no such thing and it is {@code decoder} from the start.</p>
     */
    private static String inboundCodecName(ChannelPipeline pipeline) {
        if (pipeline.get("decoder") != null) return "decoder";
        if (pipeline.get("inbound_config") != null) return "inbound_config";
        return null;
    }

    /**
     * Opens a recording. Called by the tap when login success goes past.
     *
     * @return false if capture was disarmed in the meantime or the scratch file could not be made,
     *         in which case the tap stays dormant for this connection
     */
    @Override
    public synchronized boolean beginSession(ChannelPipeline pipeline) {
        if (!armed) return false;
        if (session != null) endSessionLocked("superseded");
        try {
            Path scratch = scratchDir().resolve("clef-" + STAMP.format(Instant.now()) + ".tmcpr");
            Session started = new Session(new McprWriter(scratch), pipeline, nextSessionId++);
            this.session = started;
            // Now, not at install: this is when the codec slot has settled on its final name.
            this.pipelineNames = List.copyOf(pipeline.names());
            JsonObject data = new JsonObject();
            data.addProperty("serverName", info().serverName());
            data.addProperty("scratch", scratch.toString());
            Events.emit("replay.started", data);
            MezzoClef.LOG.info("Replay recording started -> {}", scratch);
            return true;
        } catch (IOException e) {
            MezzoClef.LOG.error("Replay recording could not start", e);
            return false;
        }
    }

    /**
     * Appends one packet.
     *
     * @return false when the recording has stopped (size cap, write error) and the tap should give
     *         up for this connection
     */
    @Override
    public boolean record(byte[] packet) {
        Session s = session;
        if (s == null || s.ended) return false;
        try {
            s.writer.write(s.elapsedMs(), packet, 0, packet.length);
        } catch (IOException e) {
            abort(e);
            return false;
        }
        long max = maxBytes();
        if (max > 0 && s.writer.bytes() >= max) {
            endSession("size_limit");
            return false;
        }
        return true;
    }

    /** A recorder fault. Ends the recording without touching the connection it was reading. */
    @Override
    public void abort(Throwable cause) {
        MezzoClef.LOG.error("Replay recording aborted", cause);
        endSession("error");
    }

    /** Ends the recording, sealing it first when {@code replay.autoSave} is on. */
    @Override
    public synchronized void endSession(String reason) {
        endSessionLocked(reason);
    }

    private void endSessionLocked(String reason) {
        Session s = session;
        if (s == null) return;
        s.ended = true;
        this.session = null;

        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        data.addProperty("durationMs", s.elapsedMs());
        data.addProperty("packets", s.writer.packets());
        data.addProperty("bytes", s.writer.bytes());
        try {
            if (config().autoSave && s.writer.packets() > 0) {
                data.addProperty("saved", seal(s, defaultName()).path().toString());
            }
        } catch (IOException e) {
            MezzoClef.LOG.error("Replay auto-save failed", e);
            data.addProperty("error", String.valueOf(e.getMessage()));
        } finally {
            try {
                s.writer.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failing close on a scratch file we are deleting.
            }
            deleteQuietly(s.writer.scratch());
        }
        Events.emit("replay.stopped", data);
        MezzoClef.LOG.info("Replay recording stopped ({}) — {} packets, {} bytes",
                reason, s.writer.packets(), s.writer.bytes());
    }

    // ---- saving ---------------------------------------------------------------------

    /**
     * Seals everything recorded so far into a finished {@code .mcpr}, without interrupting the
     * recording. Call it as often as you like; each call produces a complete replay ending at the
     * moment it was made.
     *
     * @param name file name without extension, or null for a timestamped default
     * @throws IllegalStateException if nothing is being recorded
     */
    public SaveResult save(String name) throws IOException {
        Session s = session;
        if (s == null || s.ended) {
            throw new IllegalStateException("not recording");
        }
        int durationMs = s.elapsedMs();
        Sealed sealed = seal(s, name == null || name.isBlank() ? defaultName() : name);
        // Counts come from the seal, not from the writer's live totals: the recording has kept
        // going while we zipped, and reporting numbers the file does not contain is a small lie
        // that costs somebody an afternoon.
        SaveResult result = new SaveResult(sealed.path(), durationMs, sealed.extent().packets(),
                Files.size(sealed.path()));
        Events.emit("replay.saved", result.toJson());
        return result;
    }

    /** What one {@link #save} produced. The recording is unaffected and carries on past it. */
    public record SaveResult(Path path, int durationMs, long packets, long bytes) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", path.getFileName().toString());
            o.addProperty("path", path.toString());
            o.addProperty("durationMs", durationMs);
            o.addProperty("packets", packets);
            o.addProperty("bytes", bytes);
            return o;
        }
    }

    private Sealed seal(Session s, String name) throws IOException {
        Path out = replayDir().resolve(sanitize(name) + ".mcpr");
        McprWriter.ReplayMetadata meta =
                new McprWriter.ReplayMetadata(info(), s.startedAtEpochMs, s.elapsedMs(), GENERATOR);
        List<ReplayMarker> markers;
        synchronized (s.markers) {
            markers = List.copyOf(s.markers);
        }
        McprWriter.Extent extent = s.writer.seal(out, meta, markers);
        MezzoClef.LOG.info("Replay sealed -> {} ({} packets)", out, extent.packets());
        return new Sealed(out, extent);
    }

    /** A sealed file and how much of the recording actually went into it. */
    private record Sealed(Path path, McprWriter.Extent extent) {}

    /** Adds a timeline marker at the current point of the recording, at the bot's last known pose. */
    public ReplayMarker marker(String name) {
        Session s = session;
        if (s == null || s.ended) throw new IllegalStateException("not recording");
        double[] at = pose;
        ReplayMarker marker = new ReplayMarker(name, s.elapsedMs(), at[0], at[1], at[2],
                (float) at[3], (float) at[4], 0f);
        synchronized (s.markers) {
            s.markers.add(marker);
        }
        return marker;
    }

    // ---- metadata from the client thread --------------------------------------------

    /**
     * Pushes the current session facts in. Called from the client tick, so sealing never has to
     * reach into Minecraft from a netty or control-plane thread.
     */
    public void refresh(ReplaySessionInfo latest) {
        if (latest != null) this.info = latest;
    }

    private ReplaySessionInfo info() {
        ReplaySessionInfo current = this.info;
        return current != null ? current
                : ReplaySessionInfo.unknown(Platform.get().minecraftVersion(), -1);
    }

    // ---- reporting ------------------------------------------------------------------

    public JsonObject status() {
        Session s = session;
        ReplaySessionInfo current = info();
        JsonObject o = new JsonObject();
        o.addProperty("supported", supported);
        o.addProperty("armed", armed);
        o.addProperty("recording", s != null && !s.ended);
        o.addProperty("dir", replayDir().toString());
        o.addProperty("autoSave", config().autoSave);
        o.addProperty("maxBytes", maxBytes());
        o.addProperty("serverName", current.serverName());
        o.addProperty("mcVersion", current.mcVersion());
        o.addProperty("protocol", current.protocolVersion());
        o.addProperty("selfId", current.selfEntityId());
        // The bot's own body is synthesized rather than captured, so say plainly whether that
        // worked: a replay missing it still opens, and nothing else would ever tell you.
        o.addProperty("selfTrack", selfTrackState);
        if (selfTrackDetail != null) o.addProperty("selfTrackDetail", selfTrackDetail);
        if (s != null) {
            o.addProperty("durationMs", s.elapsedMs());
            o.addProperty("packets", s.writer.packets());
            o.addProperty("selfPackets", s.injected.get());
            o.addProperty("bytes", s.writer.bytes());
            o.addProperty("startedAt", s.startedAtEpochMs);
            synchronized (s.markers) {
                o.addProperty("markers", s.markers.size());
            }
        }
        // The handler order is the evidence for what the recorded bytes actually are: the tap must
        // sit after any ViaFabricPlus translation and immediately before "decoder".
        JsonArray pipeline = new JsonArray();
        for (String handler : pipelineNames) pipeline.add(handler);
        o.add("pipeline", pipeline);
        return o;
    }

    /** Sealed replays on disk, newest first. */
    public JsonArray list() {
        List<Path> files = new ArrayList<>();
        Path dir = replayDir();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.mcpr")) {
                for (Path p : stream) files.add(p);
            } catch (IOException e) {
                MezzoClef.LOG.warn("Could not list replays in {}: {}", dir, e.toString());
            }
        }
        files.sort((a, b) -> Long.compare(modified(b), modified(a)));
        JsonArray out = new JsonArray();
        for (Path p : files) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getFileName().toString());
            o.addProperty("path", p.toString());
            o.addProperty("bytes", sizeOf(p));
            o.addProperty("modified", modified(p));
            out.add(o);
        }
        return out;
    }

    // ---- paths and config -----------------------------------------------------------

    private static ClefConfig.Replay config() {
        return MezzoClef.config().replay;
    }

    /** Where sealed replays land. Named to match ReplayMod's own folder, so a real client finds them. */
    public Path replayDir() {
        return Platform.get().gameDir().resolve(config().dir);
    }

    private Path scratchDir() throws IOException {
        Path dir = MezzoClef.dataDir().resolve("replay-scratch");
        Files.createDirectories(dir);
        return dir;
    }

    private static long maxBytes() {
        int mb = config().maxSizeMb;
        return mb <= 0 ? 0L : (long) mb * 1024L * 1024L;
    }

    private String defaultName() {
        return sanitize(info().serverName()) + "_" + STAMP.format(Instant.now());
    }

    /**
     * Reduces a caller-supplied name to something that cannot escape the replay directory or
     * surprise a filesystem. Everything outside {@code [A-Za-z0-9._-]} becomes an underscore.
     */
    static String sanitize(String raw) {
        String cleaned = (raw == null ? "" : raw).trim().replaceAll("[^A-Za-z0-9._-]", "_");
        // Leading dots would hide the file; an empty result would produce a bare ".mcpr".
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.length() > 96) cleaned = cleaned.substring(0, 96);
        return cleaned.isEmpty() ? "replay" : cleaned.toLowerCase(Locale.ROOT);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            MezzoClef.LOG.warn("Could not remove replay scratch {}: {}", p, e.toString());
        }
    }
}
