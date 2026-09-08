package dev.mezzo.clef.replay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * The netty handler that copies clientbound packets out of the connection and into a
 * {@link ReplayRecorder}. One instance per connection.
 *
 * <h2>Where it sits, and why exactly there</h2>
 * Immediately <b>before</b> Minecraft's inbound codec, which puts it after {@code splitter},
 * {@code decrypt} and {@code decompress}. Every message reaching it is therefore one whole packet,
 * in plaintext, uncompressed, still serialized — byte-for-byte what {@code recording.tmcpr} wants.
 * Nothing here deserializes a packet, so nothing here has to be updated when Minecraft reshuffles
 * its packet classes; the tap is the same code on 1.14.4 and 26.2.
 *
 * <p>It also lands <i>downstream</i> of ViaFabricPlus's translation handlers, which install
 * themselves when the pipeline is built (before {@code channelActive}, where we insert). Observed
 * on 26.2 against a live server, the inbound half reads
 * {@code splitter → via-decoder → via-flow-control → clef-replay-tap → decoder}. So a recording
 * made against a 1.12.2 server contains this client's native packets, not 1.12.2 ones, and opens in
 * a ReplayMod of the client's own version. {@code replay.status} prints the actual inbound handler
 * order so that claim is checkable rather than asserted, and {@code scripts/e2e.sh} asserts it.
 *
 * <h2>Where a recording starts</h2>
 * Not at connect: at <b>login success</b>. A replay is played back by feeding the file into a
 * client that begins in the login state, so it must start with the login success packet and must
 * not contain the encryption or compression negotiation that preceded it — those describe a
 * transport the file no longer has. Login success is clientbound packet id {@code 0x02}, which has
 * been true of every protocol in the supported range, and it is the first thing a status ping never
 * sends, so the same gate also quietly ignores the server-list pings the {@code auto} protocol
 * detector makes.
 *
 * <p>Everything after that is recorded verbatim, configuration phase included.</p>
 */
public final class ReplayTap extends ChannelInboundHandlerAdapter {

    /** Pipeline handler name. Public so {@code replay.status} can point at it in the handler list. */
    public static final String NAME = "clef-replay-tap";

    /** Clientbound login-phase packet id for Login Success. Stable 1.7 → 26.2. */
    private static final int LOGIN_SUCCESS_ID = 0x02;

    /**
     * What the tap talks to. {@link ReplayRecorder} is the only real implementation; the interface
     * exists so the gate below — which is the fiddly part, and the part a protocol change would
     * break — can be driven by a test through an {@code EmbeddedChannel} without a game.
     */
    public interface Sink {
        /**
         * Opens a recording, handing over the pipeline the tap is living in. False means "not now",
         * and the tap stays dormant for this connection.
         *
         * <p>The pipeline comes along because a recording needs more than the bytes on the wire: to
         * put the bot's own body in the replay we have to <i>encode</i> packets the server never
         * sent, and the only encoder guaranteed to agree with what we are recording is the one
         * sitting in the {@code decoder} slot right next to us.</p>
         */
        boolean beginSession(io.netty.channel.ChannelPipeline pipeline);

        /** Appends a packet. False means the recording is over and the tap should stop. */
        boolean record(byte[] packet);

        /** The connection went away. */
        void endSession(String reason);

        /** Something in the recorder threw; the connection itself is fine. */
        void abort(Throwable cause);
    }

    private final Sink recorder;

    /** False until the login success packet has gone past; nothing before it is recorded. */
    private boolean live;

    public ReplayTap(Sink recorder) {
        this.recorder = recorder;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof ByteBuf) {
                capture(ctx, (ByteBuf) msg);
            }
        } catch (Throwable t) {
            // A recorder fault must never cost the bot its connection: drop the recording, keep
            // the packet moving.
            recorder.abort(t);
            live = false;
        }
        ctx.fireChannelRead(msg);
    }

    /** Non-destructive: reads by absolute index so the decoder still sees a full buffer. */
    private void capture(ChannelHandlerContext ctx, ByteBuf buf) {
        int length = buf.readableBytes();
        if (length <= 0) return;
        if (!live) {
            if (peekVarInt(buf) != LOGIN_SUCCESS_ID) return;
            if (!recorder.beginSession(ctx.pipeline())) return;
            live = true;
        }
        byte[] copy = new byte[length];
        buf.getBytes(buf.readerIndex(), copy);
        if (!recorder.record(copy)) live = false;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (live) {
            live = false;
            recorder.endSession("disconnected");
        }
        ctx.fireChannelInactive();
    }

    /**
     * Reads the leading varint without consuming it. Returns {@code -1} for a truncated or
     * over-long varint, which cannot be a login success and is therefore simply not our packet.
     */
    static int peekVarInt(ByteBuf buf) {
        int index = buf.readerIndex();
        int end = buf.writerIndex();
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (index >= end) return -1;
            int b = buf.getByte(index++) & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }
}
