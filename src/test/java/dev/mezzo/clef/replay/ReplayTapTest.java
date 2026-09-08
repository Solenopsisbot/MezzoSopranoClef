package dev.mezzo.clef.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tap's whole job is a gate — start at login success, copy without consuming, never break the
 * connection — and every one of those is invisible to the compiler. An {@link EmbeddedChannel}
 * exercises it with no game attached.
 */
class ReplayTapTest {

    /** Records what the tap handed over, and lets a test make the recorder misbehave. */
    private static class FakeSink implements ReplayTap.Sink {
        long nextSession = 1;
        final List<Long> recordedFor = new ArrayList<>();
        final List<Long> endedFor = new ArrayList<>();
        final List<byte[]> packets = new ArrayList<>();
        final List<String> endings = new ArrayList<>();
        Throwable aborted;
        io.netty.channel.ChannelPipeline pipeline;
        boolean acceptSession = true;
        int stopAfter = Integer.MAX_VALUE;

        @Override public long beginSession(io.netty.channel.ChannelPipeline pipeline) {
            this.pipeline = pipeline;
            return acceptSession ? nextSession++ : 0L;
        }

        @Override public boolean record(long session, byte[] packet) {
            packets.add(packet);
            recordedFor.add(session);
            return packets.size() < stopAfter;
        }

        @Override public void endSession(long session, String reason) {
            endings.add(reason);
            endedFor.add(session);
        }

        @Override public void abort(long session, Throwable cause) {
            aborted = cause;
        }
    }

    private static ByteBuf packet(int id, int... body) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(id);
        for (int b : body) buf.writeByte(b);
        return buf;
    }

    @Test
    void ignoresEverythingBeforeLoginSuccess() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x01, 0xAA));   // encryption request
        channel.writeInbound(packet(0x03, 0x80));   // set compression
        assertTrue(sink.packets.isEmpty(), "login negotiation describes a transport the file has not got");
        channel.finishAndReleaseAll();
    }

    @Test
    void startsAtLoginSuccessAndKeepsEverythingAfter() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x01, 0xAA));
        channel.writeInbound(packet(0x02, 'i', 'd'));
        channel.writeInbound(packet(0x27, 1, 2));
        channel.writeInbound(packet(0x00));         // id 0 is fine once we are past the gate

        assertEquals(3, sink.packets.size());
        assertArrayEquals(new byte[] {0x02, 'i', 'd'}, sink.packets.get(0));
        assertArrayEquals(new byte[] {0x27, 1, 2}, sink.packets.get(1));
        assertArrayEquals(new byte[] {0x00}, sink.packets.get(2));
        channel.finishAndReleaseAll();
    }

    @Test
    void passesBuffersThroughUnreadSoTheDecoderStillSeesThem() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        ByteBuf sent = packet(0x02, 'x');
        int readerIndex = sent.readerIndex();
        channel.writeInbound(sent);

        ByteBuf forwarded = channel.readInbound();
        assertEquals(readerIndex, forwarded.readerIndex(), "the tap must not consume the packet");
        assertEquals(2, forwarded.readableBytes());
        forwarded.release();
        channel.finishAndReleaseAll();
    }

    /** A status ping never sends login success, so the same gate keeps protocol probes out. */
    @Test
    void neverRecordsAStatusPing() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x00, '{', '}'));   // status response
        channel.writeInbound(packet(0x01, 0, 0, 0, 1)); // pong
        channel.close();
        assertTrue(sink.packets.isEmpty());
        assertTrue(sink.endings.isEmpty(), "a connection that never recorded has nothing to end");
        channel.finishAndReleaseAll();
    }

    @Test
    void reportsTheDisconnectOnlyForALiveRecording() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02));
        channel.close();
        assertEquals(List.of("disconnected"), sink.endings);
        channel.finishAndReleaseAll();
    }

    /** The recorder needs the pipeline to encode the bot's own body into the same stream. */
    @Test
    void handsTheRecorderThePipelineItIsLivingIn() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02));
        assertSame(channel.pipeline(), sink.pipeline);
        channel.finishAndReleaseAll();
    }

    @Test
    void staysDormantWhenTheRecorderDeclinesTheSession() {
        FakeSink sink = new FakeSink();
        sink.acceptSession = false;
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02));
        channel.writeInbound(packet(0x27));
        assertTrue(sink.packets.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test
    void stopsCopyingOnceTheRecorderSaysItIsDone() {
        FakeSink sink = new FakeSink();
        sink.stopAfter = 2;   // e.g. the size cap tripped
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02));
        channel.writeInbound(packet(0x27));
        channel.writeInbound(packet(0x28));
        channel.writeInbound(packet(0x29));
        assertEquals(2, sink.packets.size());
        channel.finishAndReleaseAll();
    }

    /** A recorder fault must cost the recording, never the bot's connection. */
    @Test
    void survivesARecorderThatThrows() {
        FakeSink sink = new FakeSink() {
            @Override public long beginSession(io.netty.channel.ChannelPipeline pipeline) {
                throw new IllegalStateException("disk on fire");
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02, 'x'));

        assertTrue(channel.isActive(), "the connection must survive a recorder fault");
        ByteBuf forwarded = channel.readInbound();
        assertEquals(2, forwarded.readableBytes(), "the packet still has to reach the decoder");
        forwarded.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void ignoresEmptyBuffers() {
        FakeSink sink = new FakeSink();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(Unpooled.EMPTY_BUFFER);
        assertTrue(sink.packets.isEmpty());
        channel.finishAndReleaseAll();
    }

    /**
     * The regression that matters most here. In the PLAY protocol clientbound 0x02 is
     * ClientboundAnimatePacket — any nearby entity swinging an arm — so a login gate that re-arms
     * after the recording stops opens a second "recording" that begins with an animate instead of a
     * login success: an .mcpr that opens and cannot play. With autoSave on and a size cap set it
     * would do that on a loop.
     */
    @Test
    void neverReopensAfterTheRecordingHasStopped() {
        FakeSink sink = new FakeSink();
        sink.stopAfter = 1;                       // e.g. the size cap trips on the first packet
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02));       // login success: opens session 1, then stops
        assertEquals(1, sink.packets.size());

        channel.writeInbound(packet(0x02, 0x07, 0x00));   // an animate, in the play phase
        channel.writeInbound(packet(0x02, 0x08, 0x00));
        assertEquals(1, sink.packets.size(), "the login gate must not re-arm");
        assertEquals(1, sink.nextSession - 1, "no second session may be opened");

        channel.close();
        assertTrue(sink.endings.isEmpty(), "a stopped recording must not be ended twice");
        channel.finishAndReleaseAll();
    }

    /**
     * The bot's reconnect path leaves both sockets open until the server drops the first, so the
     * losing tap keeps delivering packets after a newer connection has opened its own recording.
     * Every call carries the session it belongs to precisely so the recorder can tell them apart.
     */
    @Test
    void tagsEveryCallWithTheSessionItOpened() {
        FakeSink sink = new FakeSink();
        sink.nextSession = 7;
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayTap(sink));
        channel.writeInbound(packet(0x02));
        channel.writeInbound(packet(0x27));
        channel.close();

        assertEquals(List.of(7L, 7L), sink.recordedFor);
        assertEquals(List.of(7L), sink.endedFor);
        channel.finishAndReleaseAll();
    }

    @Test
    void readsVarIntsWithoutConsumingThem() {
        ByteBuf single = Unpooled.wrappedBuffer(new byte[] {0x02, 0x7F});
        assertEquals(2, ReplayTap.peekVarInt(single));
        assertEquals(2, single.readableBytes(), "peeking must leave the buffer alone");

        // 300 = 0xAC 0x02, the classic two-byte case.
        assertEquals(300, ReplayTap.peekVarInt(Unpooled.wrappedBuffer(new byte[] {(byte) 0xAC, 0x02})));
        // Truncated and over-long varints are not packet ids we care about, and must not throw.
        assertEquals(-1, ReplayTap.peekVarInt(Unpooled.wrappedBuffer(new byte[] {(byte) 0x80})));
        assertEquals(-1, ReplayTap.peekVarInt(Unpooled.EMPTY_BUFFER));
        assertEquals(-1, ReplayTap.peekVarInt(Unpooled.wrappedBuffer(
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01})));
    }
}
