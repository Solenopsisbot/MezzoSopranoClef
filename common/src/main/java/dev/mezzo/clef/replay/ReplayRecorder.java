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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * Four threads reach this class and none of them may be made to wait on a disk:
 * <ul>
 *   <li><b>netty IO</b> — {@link #beginSession}, {@link #record}, {@link #endSession}. Blocking
 *       here stops the connection dead: the tap sits upstream of the decoder, so nothing inbound is
 *       delivered and no keep-alive reply is flushed, and vanilla kicks an unanswered keep-alive
 *       after 15s. A 512 MiB deflate is comfortably longer than that.</li>
 *   <li><b>client tick</b> — {@link #inject}, {@link #pose}, {@link #refresh}, {@link #marker}.
 *       Blocking here freezes combat, crafting and input.</li>
 *   <li><b>control plane</b> — {@link #save}, {@link #status}, {@link #list}, {@link #setArmed}.
 *       This one is allowed to block; the caller asked for a file and is waiting for its path.</li>
 *   <li><b>{@code clef-replay} worker</b> — every automatic seal and <i>every</i> event emission.
 *       Both are unbounded-latency operations (a deflate; a socket write to a subscriber that has
 *       stopped reading), so neither belongs on a thread the game needs back.</li>
 * </ul>
 * Nothing in {@code common/} ever touches a Minecraft object, which is why the client-thread half
 * is a snapshot pushed in rather than a lookup pulled out.
 */
public final class ReplayRecorder implements ReplayTap.Sink {

    private static final ReplayRecorder INSTANCE = new ReplayRecorder();

    /**
     * Milliseconds included deliberately. Two seals inside the same second — an auto-save racing a
     * {@code replay.save}, or two quick saves — would otherwise resolve to the same file name.
     */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss.SSS").withZone(ZoneId.systemDefault());

    /** Written into {@code metaData.json} so a replay's origin is obvious in ReplayStudio. */
    private static final String GENERATOR = "MezzoSopranoClef";

    /** A scratch file untouched for this long at startup belongs to a run that died. */
    private static final long STALE_SCRATCH_MS = TimeUnit.HOURS.toMillis(1);

    public static ReplayRecorder get() {
        return INSTANCE;
    }

    /** One connection's recording. Identified by {@link #id}, which is how the tap addresses it. */
    private static final class Session {
        final McprWriter writer;
        /** The connection this recording is reading. Dropped with the session, so nothing is pinned. */
        final ChannelPipeline pipeline;
        final long id;
        final long startedAtEpochMs = System.currentTimeMillis();
        final long startedAtNanos = System.nanoTime();
        final List<ReplayMarker> markers = new ArrayList<>();
        /** Packets this recording added that the server never sent — see {@link #inject}. */
        final AtomicLong injected = new AtomicLong();
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
     * Seals and event emission, off every thread that matters. Single-threaded so a queued seal and
     * the events describing it stay in order.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clef-replay");
        thread.setDaemon(true);
        return thread;
    });

    private long nextSessionId = 1;
    /**
     * Whether this build can record at all. Set by the client entrypoint of a target that carries
     * the {@code Connection} mixin the tap is installed from. Everywhere else the commands still
     * register and still answer — they just say honestly that the target cannot do it yet, which
     * beats the command vanishing from {@code help} on half the matrix.
     */
    private volatile boolean supported;
    private volatile boolean armed;
    private volatile Session session;
    /** Where the bot is, refreshed each tick while armed. Only markers need it. */
    private volatile double[] pose = {0, 0, 0, 0, 0};
    /** Last snapshot pushed in from the client thread; what sealing writes into the metadata. */
    private volatile ReplaySessionInfo info;
    /**
     * Which recording {@link #info} was read during. Metadata gathered against a different
     * connection is not "a bit stale", it is wrong — see {@link #infoFor}.
     */
    private volatile long infoSession;
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
        sweepStaleScratch();
        // Fabric's CLIENT_STOPPING only fires from Minecraft.stop(), and nothing stops this bot
        // that way: e2e.sh kills the process, verify_versions.sh pkills it, and the launcher
        // destroys it from its own shutdown hook. Without this, a kill with the connection still
        // up loses the recording AND leaves its scratch file behind.
        Runtime.getRuntime().addShutdownHook(new Thread(this::onJvmShutdown, "clef-replay-shutdown"));
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
     * {@link #record} this never trips the size cap: the cap's response is to end the recording,
     * and the client thread is not somewhere to start that from. The next real packet trips it.</p>
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
            MezzoClef.LOG.error("Replay self-tracking write failed", e);
            endSession("error");
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
     * success, which is the filter that keeps a server-list ping from producing a replay — and is
     * also why nothing here may disturb a recording already in progress on another socket.</p>
     */
    public void install(ChannelPipeline pipeline) {
        if (!armed) return;
        try {
            boolean idle = session == null;
            // Reported by replay.status either way: when a recording never starts, the handler
            // order is the first thing worth looking at, and "we could not find the decoder" is a
            // very different problem from "we installed but saw no login success". Only while idle,
            // so a status ping cannot overwrite what a live recording is reading.
            if (idle) this.pipelineNames = List.copyOf(pipeline.names());
            String anchor = inboundCodecName(pipeline);
            // An in-memory (integrated server) pipeline has neither name: packets move as objects,
            // never as bytes, so there is nothing here to copy. Singleplayer is out of scope rather
            // than silently broken.
            if (anchor == null) {
                MezzoClef.LOG.info("Replay tap skipped: no inbound codec slot in pipeline {}",
                        pipeline.names());
                return;
            }
            if (pipeline.get(ReplayTap.NAME) != null) return;
            pipeline.addBefore(anchor, ReplayTap.NAME, new ReplayTap(this));
            if (idle) this.pipelineNames = List.copyOf(pipeline.names());
            MezzoClef.LOG.info("Replay tap installed; inbound pipeline is now {}", pipeline.names());
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

    // ---- the tap's side of the contract ---------------------------------------------

    /**
     * Opens a recording. Called by the tap when login success goes past.
     *
     * @return the new session's id, or 0 if capture was disarmed in the meantime or the scratch
     *         file could not be made, in which case the tap stays dormant for this connection
     */
    @Override
    public long beginSession(ChannelPipeline pipeline) {
        Session started;
        JsonObject data = new JsonObject();
        synchronized (this) {
            if (!armed) return 0L;
            if (session != null) endSessionLocked("superseded");
            try {
                Path scratch = scratchDir().resolve("clef-" + STAMP.format(Instant.now()) + ".tmcpr");
                started = new Session(new McprWriter(scratch), pipeline, nextSessionId++);
                this.session = started;
                // Now, not at install: this is when the codec slot has settled on its final name.
                this.pipelineNames = List.copyOf(pipeline.names());
                this.selfTrackState = "pending";
                this.selfTrackDetail = null;
                data.addProperty("serverName", infoFor(started).serverName());
                data.addProperty("scratch", scratch.toString());
            } catch (IOException e) {
                MezzoClef.LOG.error("Replay recording could not start", e);
                return 0L;
            }
        }
        MezzoClef.LOG.info("Replay recording started -> {}", started.writer.scratch());
        // Emitted off the monitor and off this thread. Delivery is a blocking socket write with no
        // write timeout, and this is the netty thread holding up the login-success packet itself:
        // one subscriber that stops reading would otherwise stop the bot from finishing its join.
        emitLater("replay.started", data);
        return started.id;
    }

    /**
     * Appends one packet.
     *
     * @param token the session the calling tap opened; a tap whose recording has been superseded by
     *              a newer connection must not append to the newer one's file
     * @return false when this tap's recording is over (superseded, size cap, write error) and it
     *         should give up for good
     */
    @Override
    public boolean record(long token, byte[] packet) {
        Session s = session;
        if (s == null || s.ended || s.id != token) return false;
        try {
            s.writer.write(s.elapsedMs(), packet, 0, packet.length);
        } catch (IOException e) {
            MezzoClef.LOG.error("Replay recording aborted", e);
            endSession(token, "error");
            return false;
        }
        long max = maxBytes();
        if (max > 0 && s.writer.bytes() >= max) {
            endSession(token, "size_limit");
            return false;
        }
        return true;
    }

    /** A recorder fault on the tap's side. Ends the recording without touching the connection. */
    @Override
    public void abort(long token, Throwable cause) {
        MezzoClef.LOG.error("Replay recording aborted", cause);
        endSession(token, "error");
    }

    /** Ends {@code token}'s recording, if it is still the current one. */
    @Override
    public synchronized void endSession(long token, String reason) {
        Session s = session;
        if (s == null || s.id != token) return;
        endSessionLocked(reason);
    }

    /** Ends whatever is recording, whoever it belongs to. For disarm and shutdown. */
    public synchronized void endSession(String reason) {
        endSessionLocked(reason);
    }

    private void endSessionLocked(String reason) {
        Session s = session;
        if (s == null) return;
        s.ended = true;
        this.session = null;

        long packets = s.writer.packets();
        long bytes = s.writer.bytes();
        boolean autoSave = config().autoSave && packets > 0;
        String name = autoSave ? defaultName(s) : null;
        ReplaySessionInfo meta = infoFor(s);

        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        data.addProperty("durationMs", s.elapsedMs());
        data.addProperty("packets", packets);
        data.addProperty("bytes", bytes);
        data.addProperty("autoSaving", autoSave);
        MezzoClef.LOG.info("Replay recording stopped ({}) — {} packets, {} bytes",
                reason, packets, bytes);

        // Everything past this point is disk and socket work, and this is a netty or client thread.
        // Sealing 512 MiB is a ten-to-twenty-second deflate; doing it here turns the size cap into
        // a keep-alive timeout on the netty side and a visible freeze on the game side.
        worker.execute(() -> finish(s, name, meta, data));
    }

    /** The slow half of ending a recording: emit, seal, close, delete. Worker thread only. */
    private void finish(Session s, String name, ReplaySessionInfo meta, JsonObject stopped) {
        Events.emit("replay.stopped", stopped);
        try {
            if (name != null) {
                SaveResult saved = seal(s, name, meta);
                Events.emit("replay.saved", saved.toJson());
            }
        } catch (IOException e) {
            MezzoClef.LOG.error("Replay auto-save failed", e);
        } finally {
            try {
                s.writer.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failing close on a scratch file we are deleting.
            }
            deleteQuietly(s.writer.scratch());
        }
    }

    // ---- saving ---------------------------------------------------------------------

    /**
     * Seals everything recorded so far into a finished {@code .mcpr}, without interrupting the
     * recording. Call it as often as you like; each call produces a complete replay ending at the
     * moment it was made.
     *
     * <p>Runs the deflate on the calling thread: this is the control plane, the caller asked for a
     * file, and it wants the path back. Automatic saves go to the worker instead.</p>
     *
     * @param name file name without extension, or null for a timestamped default
     * @throws IllegalStateException if nothing is being recorded
     */
    public SaveResult save(String name) throws IOException {
        Session s = session;
        if (s == null || s.ended) {
            throw new IllegalStateException("not recording");
        }
        SaveResult result = seal(s, name == null || name.isBlank() ? defaultName(s) : name,
                infoFor(s));
        emitLater("replay.saved", result.toJson());
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

    private SaveResult seal(Session s, String name, ReplaySessionInfo meta) throws IOException {
        Path out = replayDir().resolve(sanitize(name) + ".mcpr");
        int durationMs = s.elapsedMs();
        List<ReplayMarker> markers;
        synchronized (s.markers) {
            markers = List.copyOf(s.markers);
        }
        McprWriter.Extent extent = s.writer.seal(out,
                new McprWriter.ReplayMetadata(meta, s.startedAtEpochMs, durationMs, GENERATOR),
                markers);
        MezzoClef.LOG.info("Replay sealed -> {} ({} packets)", out, extent.packets());
        // Counts come from the seal, not from the writer's live totals: the recording has kept
        // going while we zipped, and reporting numbers the file does not contain is a small lie
        // that costs somebody an afternoon.
        return new SaveResult(out, durationMs, extent.packets(), Files.size(out));
    }

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
        if (latest == null) return;
        this.info = latest;
        this.infoSession = sessionId();
    }

    /**
     * The metadata to seal {@code s} with, or honest unknowns.
     *
     * <p>A snapshot is only usable for the recording it was taken during. Login success arrives on
     * the netty thread and can beat the next client tick, so at the start of a recording the newest
     * snapshot still describes the <i>previous</i> connection — and sealing a replay of server B
     * with server A's name, player list and {@code selfId} would be a file that lies rather than a
     * file that is vague.</p>
     */
    private ReplaySessionInfo infoFor(Session s) {
        ReplaySessionInfo current = this.info;
        long taken = this.infoSession;
        if (current != null && taken == (s == null ? 0L : s.id)) return current;
        return ReplaySessionInfo.unknown(Platform.get().minecraftVersion(), -1);
    }

    // ---- reporting ------------------------------------------------------------------

    public JsonObject status() {
        Session s = session;
        ReplaySessionInfo current = infoFor(s);
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

    // ---- shutdown -------------------------------------------------------------------

    /**
     * Last chance to keep a recording. Runs on SIGTERM, which is how every script in this repo
     * stops the bot, and is the only stop path that reaches the code at all.
     *
     * <p>Bounded: a seal that cannot finish inside the grace period loses the replay but not the
     * shutdown, and the scratch file it leaves behind is swept on the next start.</p>
     */
    private void onJvmShutdown() {
        try {
            endSession("shutdown");
            worker.shutdown();
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                MezzoClef.LOG.warn("Replay seal did not finish within the shutdown grace period");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Replay shutdown handling failed: {}", t.toString());
        }
    }

    /**
     * Removes scratch files from runs that were killed before they could clean up. Anything still
     * being written to is minutes fresh, so age is a safe discriminator even if another client is
     * sharing this game directory.
     */
    private void sweepStaleScratch() {
        try {
            Path dir = scratchDir();
            long cutoff = System.currentTimeMillis() - STALE_SCRATCH_MS;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tmcpr")) {
                for (Path p : stream) {
                    if (modified(p) < cutoff) {
                        MezzoClef.LOG.info("Removing stale replay scratch {}", p.getFileName());
                        deleteQuietly(p);
                    }
                }
            }
        } catch (IOException e) {
            MezzoClef.LOG.warn("Could not sweep replay scratch: {}", e.toString());
        }
    }

    // ---- paths and config -----------------------------------------------------------

    /** Hands an event to the worker. See the threading note on this class for why. */
    private void emitLater(String event, JsonObject data) {
        try {
            worker.execute(() -> Events.emit(event, data));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // The JVM is on its way out and nobody is listening any more.
        }
    }

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

    private String defaultName(Session s) {
        return sanitize(infoFor(s).serverName()) + "_" + STAMP.format(Instant.now());
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
