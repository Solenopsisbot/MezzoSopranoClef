package dev.mezzo.clef.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The .mcpr container is a contract with software we do not control, so these tests read the file
 * back the way ReplayMod would rather than asserting on our own writer's internals.
 */
class McprWriterTest {

    private static final ReplaySessionInfo SESSION = new ReplaySessionInfo(
            false, "mc.example:25565", "26.2", 1234, 42, List.of("uuid-a", "uuid-b"));

    @Test
    void framesPacketsTheWayReplayModReadsThem(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.mcpr");
        try (McprWriter writer = new McprWriter(dir.resolve("scratch.tmcpr"))) {
            writer.write(0, new byte[] {0x02, 'h', 'i'}, 0, 3);
            writer.write(1500, new byte[] {0x20, 1, 2, 3, 4}, 0, 5);
            writer.seal(out, meta(1500), List.of());
        }

        List<Packet> packets = readRecording(out);
        assertEquals(2, packets.size());
        assertEquals(0, packets.get(0).timestamp);
        assertArrayEquals(new byte[] {0x02, 'h', 'i'}, packets.get(0).data);
        assertEquals(1500, packets.get(1).timestamp);
        assertArrayEquals(new byte[] {0x20, 1, 2, 3, 4}, packets.get(1).data);
    }

    /**
     * The point of the whole design: a snapshot taken while the bot is still playing must be a
     * complete replay of everything up to that instant, and must not disturb the recording.
     */
    @Test
    void sealsRepeatedlyWhileStillRecording(@TempDir Path dir) throws IOException {
        try (McprWriter writer = new McprWriter(dir.resolve("scratch.tmcpr"))) {
            writer.write(0, new byte[] {0x02}, 0, 1);
            writer.write(10, new byte[] {0x10}, 0, 1);
            Path first = dir.resolve("first.mcpr");
            writer.seal(first, meta(10), List.of());

            writer.write(20, new byte[] {0x11}, 0, 1);
            Path second = dir.resolve("second.mcpr");
            writer.seal(second, meta(20), List.of());

            assertEquals(2, readRecording(first).size(), "the first snapshot must not grow later");
            assertEquals(3, readRecording(second).size());
        }
    }

    /** What a seal reports has to describe the file, not the recording that has moved on past it. */
    @Test
    void reportsWhatWentIntoTheFileNotWhatHasBeenRecorded(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("counts.mcpr");
        try (McprWriter writer = new McprWriter(dir.resolve("scratch.tmcpr"))) {
            writer.write(0, new byte[] {0x02}, 0, 1);
            writer.write(1, new byte[] {0x10}, 0, 1);
            McprWriter.Extent sealed = writer.seal(out, meta(1), List.of());
            writer.write(2, new byte[] {0x11}, 0, 1);

            assertEquals(2, sealed.packets());
            assertEquals(readRecording(out).size(), sealed.packets());
            assertEquals(3, writer.packets(), "the live counter keeps going, and is not the answer");
        }
    }

    @Test
    void writesTheMetadataFieldsReplayStudioExpects(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("meta.mcpr");
        try (McprWriter writer = new McprWriter(dir.resolve("scratch.tmcpr"))) {
            writer.write(0, new byte[] {0x02}, 0, 1);
            writer.seal(out, meta(7777), List.of());
        }

        JsonObject meta = readJson(out, "metaData.json");
        assertEquals("MCPR", meta.get("fileFormat").getAsString());
        assertEquals(McprWriter.FILE_FORMAT_VERSION, meta.get("fileFormatVersion").getAsInt());
        assertEquals("mc.example:25565", meta.get("serverName").getAsString());
        assertEquals("26.2", meta.get("mcversion").getAsString());
        assertEquals(1234, meta.get("protocol").getAsInt());
        assertEquals(42, meta.get("selfId").getAsInt());
        assertEquals(7777, meta.get("duration").getAsInt());
        assertFalse(meta.get("singleplayer").getAsBoolean());
        assertEquals(2, meta.getAsJsonArray("players").size());
    }

    @Test
    void omitsMarkersFileWhenThereAreNone(@TempDir Path dir) throws IOException {
        Path bare = dir.resolve("bare.mcpr");
        Path marked = dir.resolve("marked.mcpr");
        try (McprWriter writer = new McprWriter(dir.resolve("scratch.tmcpr"))) {
            writer.write(0, new byte[] {0x02}, 0, 1);
            writer.seal(bare, meta(1), List.of());
            writer.seal(marked, meta(1),
                    List.of(new ReplayMarker("found diamonds", 900, 1, 2, 3, 90f, 0f, 0f)));
        }

        assertFalse(hasEntry(bare, "markers.json"), "an empty markers.json is worse than none");
        assertTrue(hasEntry(marked, "markers.json"));
        assertEquals("found diamonds",
                readJsonArrayFirst(marked, "markers.json").get("name").getAsString());
    }

    @Test
    void leavesNoPartFileBehindOnSuccess(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("clean.mcpr");
        try (McprWriter writer = new McprWriter(dir.resolve("scratch.tmcpr"))) {
            writer.write(0, new byte[] {0x02}, 0, 1);
            writer.seal(out, meta(1), List.of());
        }
        assertTrue(Files.exists(out));
        assertFalse(Files.exists(dir.resolve("clean.mcpr.part")));
    }

    // ---- helpers --------------------------------------------------------------------

    private static McprWriter.ReplayMetadata meta(int durationMs) {
        return new McprWriter.ReplayMetadata(SESSION, 1_700_000_000_000L, durationMs, "test");
    }

    private record Packet(int timestamp, byte[] data) {}

    /** Decodes recording.tmcpr exactly as the format specifies: [int32 time][int32 len][bytes]. */
    private static List<Packet> readRecording(Path mcpr) throws IOException {
        List<Packet> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(mcpr.toFile());
             DataInputStream in = new DataInputStream(
                     zip.getInputStream(zip.getEntry("recording.tmcpr")))) {
            while (true) {
                int timestamp;
                try {
                    timestamp = in.readInt();
                } catch (java.io.EOFException end) {
                    break;
                }
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                out.add(new Packet(timestamp, data));
            }
        }
        return out;
    }

    private static JsonObject readJson(Path mcpr, String entry) throws IOException {
        return com.google.gson.JsonParser.parseString(readEntry(mcpr, entry)).getAsJsonObject();
    }

    private static JsonObject readJsonArrayFirst(Path mcpr, String entry) throws IOException {
        return com.google.gson.JsonParser.parseString(readEntry(mcpr, entry))
                .getAsJsonArray().get(0).getAsJsonObject();
    }

    private static String readEntry(Path mcpr, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(mcpr.toFile());
             InputStream in = zip.getInputStream(zip.getEntry(entry))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static boolean hasEntry(Path mcpr, String name) throws IOException {
        try (ZipFile zip = new ZipFile(mcpr.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            return entry != null;
        }
    }
}
