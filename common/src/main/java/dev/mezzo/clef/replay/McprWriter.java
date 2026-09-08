package dev.mezzo.clef.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a ReplayMod-compatible recording: a growing {@code .tmcpr} packet log that can be sealed
 * into a {@code .mcpr} archive at any moment, as many times as you like.
 *
 * <h2>The format</h2>
 * A {@code .mcpr} is a zip. The only entry that matters is {@code recording.tmcpr}, which is a flat
 * sequence of
 * <pre>[int32 BE timestamp ms][int32 BE length][length bytes: varint packet id + packet body]</pre>
 * — clientbound packets only, already decompressed and decrypted, in the wire encoding of
 * {@code metaData.json}'s {@code protocol}. That is exactly the shape of the bytes sitting in the
 * pipeline immediately before Minecraft's own {@code decoder}, which is why {@link ReplayTap} can
 * copy them straight through without ever deserializing a packet.
 *
 * <h2>Why a scratch file and a separate seal</h2>
 * Recording appends to a plain file; sealing zips a <i>prefix</i> of it. Nothing about the packet
 * log depends on the recording having finished, so a snapshot taken mid-session is a complete,
 * openable replay of everything up to that instant — which is what makes "watch what the bot is
 * doing, right now" possible without a second client. Appends never rewrite earlier bytes, so the
 * copy is safe to make while recording continues past it; {@link #seal} only has to agree with the
 * writer on where the last whole packet ended, which is what {@link #flushToDisk()} returns.
 *
 * <p>Instances are thread-safe: packets arrive on a netty IO thread while seals are requested from
 * a control-plane thread.</p>
 */
public final class McprWriter implements Closeable {

    /** ReplayMod's current container version. 14 is what 1.20+ ReplayMod writes and reads. */
    public static final int FILE_FORMAT_VERSION = 14;

    static final String ENTRY_RECORDING = "recording.tmcpr";
    static final String ENTRY_METADATA = "metaData.json";
    static final String ENTRY_MARKERS = "markers.json";

    private final Path scratch;
    private final OutputStream out;
    /**
     * Held for the duration of a seal, and deliberately not the same monitor as {@link #write}: two
     * seals of one recording must not interleave (an auto-save racing a {@code replay.save}, or two
     * saves with the same name), but recording has to carry on through both.
     */
    private final Object sealLock = new Object();

    private long bytes;
    private long packets;
    private int lastStamp;
    private boolean closed;

    public McprWriter(Path scratch) throws IOException {
        this.scratch = scratch;
        Files.createDirectories(scratch.toAbsolutePath().getParent());
        this.out = new BufferedOutputStream(Files.newOutputStream(scratch), 1 << 16);
    }

    /**
     * Appends one clientbound packet.
     *
     * @param timestampMs milliseconds since the recording started, clamped into int range — the
     *                    format has no room for more, which caps a single replay at ~24 days — and
     *                    clamped to be non-decreasing, so concurrent writers cannot fold the
     *                    timeline back on itself
     */
    public synchronized void write(long timestampMs, byte[] packet, int off, int len) throws IOException {
        if (closed) return;
        int stamp = (int) Math.max(0, Math.min(Integer.MAX_VALUE, timestampMs));
        // Two threads write here — the netty thread for real packets, the client thread for the
        // bot's own synthetic ones — and each reads the clock before taking the lock, so their
        // stamps can cross by a millisecond or two. A replay timeline that goes backwards is not
        // worth the argument: clamp, and let the ordering the packets were written in stand.
        stamp = Math.max(stamp, lastStamp);
        lastStamp = stamp;
        writeInt(stamp);
        writeInt(len);
        out.write(packet, off, len);
        bytes += 8L + len;
        packets++;
    }

    private void writeInt(int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** Bytes of {@code recording.tmcpr} produced so far, including the 8-byte per-packet header. */
    public synchronized long bytes() {
        return bytes;
    }

    public synchronized long packets() {
        return packets;
    }

    public Path scratch() {
        return scratch;
    }

    /**
     * Pushes everything buffered to the file and reports the prefix that is now on disk and ends on
     * a packet boundary. Sealing reads exactly that prefix, so a packet appended concurrently can
     * never land half-written inside a snapshot — and the counts come from the same locked read, so
     * what a caller reports about a sealed file describes the file rather than the live recording,
     * which has already moved on.
     */
    public synchronized Extent flushToDisk() throws IOException {
        if (!closed) out.flush();
        return new Extent(bytes, packets);
    }

    /** How much of the recording a snapshot covers: bytes of {@code tmcpr}, and packets in them. */
    public record Extent(long bytes, long packets) {}

    /**
     * Writes a complete {@code .mcpr} containing the recording so far.
     *
     * <p>Zipped to a sibling temp file and then moved into place, so a reader watching the replay
     * folder never sees a half-written archive with a plausible name.</p>
     *
     * @param target  where the finished archive goes
     * @param meta    metadata; its {@code duration} should already reflect the snapshot point
     * @param markers timeline markers, or empty — {@code markers.json} is omitted when there are none
     * @return exactly what went into the file, which is not the same as what has been recorded by
     *         the time this returns
     */
    public Extent seal(Path target, ReplayMetadata meta, List<ReplayMarker> markers) throws IOException {
        synchronized (sealLock) {
            return sealLocked(target, meta, markers);
        }
    }

    private Extent sealLocked(Path target, ReplayMetadata meta, List<ReplayMarker> markers)
            throws IOException {
        Extent extent = flushToDisk();
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        // A unique temp rather than "<target>.part": two seals racing on a shared deterministic
        // name would open the same file and interleave two zip streams into it, and the loser's
        // move would then either fail or clobber a result somebody was already told about.
        //
        // Side effect worth keeping: createTempFile makes the file owner-only, and the move carries
        // that through, so sealed replays are 0600 rather than umask default. A replay is a
        // complete record of everything the bot saw, chat included; on a shared host that is the
        // permission it should have had anyway. Widen it deliberately if you are serving them.
        Path tmp = Files.createTempFile(dir, "clef-", ".mcpr.part");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            zip.putNextEntry(new ZipEntry(ENTRY_RECORDING));
            copyPrefix(scratch, extent.bytes(), zip);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ENTRY_METADATA));
            zip.write(meta.toJson().toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            if (!markers.isEmpty()) {
                JsonArray array = new JsonArray();
                for (ReplayMarker marker : markers) array.add(marker.toJson());
                zip.putNextEntry(new ZipEntry(ENTRY_MARKERS));
                zip.write(array.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return extent;
    }

    /** Streams the first {@code length} bytes of {@code file} into {@code sink}. */
    private static void copyPrefix(Path file, long length, OutputStream sink) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long remaining = length;
        try (InputStream in = Files.newInputStream(file)) {
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read < 0) break;
                sink.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        out.close();
    }

    /** True once {@link #close} has run; further {@link #write} calls are ignored. */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * The contents of {@code metaData.json}. Field names are ReplayStudio's
     * {@code ReplayMetaData} and are load-bearing — see {@link ReplaySessionInfo}.
     */
    public record ReplayMetadata(ReplaySessionInfo session, long startedAtEpochMs, int durationMs,
                                 String generator) {

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("singleplayer", session.singleplayer());
            o.addProperty("serverName", session.serverName());
            o.addProperty("duration", durationMs);
            o.addProperty("date", startedAtEpochMs);
            o.addProperty("mcversion", session.mcVersion());
            o.addProperty("fileFormat", "MCPR");
            o.addProperty("fileFormatVersion", FILE_FORMAT_VERSION);
            o.addProperty("protocol", session.protocolVersion());
            o.addProperty("generator", generator);
            o.addProperty("selfId", session.selfEntityId());
            JsonArray players = new JsonArray();
            for (String uuid : session.playerUuids()) players.add(uuid);
            o.add("players", players);
            return o;
        }
    }
}
