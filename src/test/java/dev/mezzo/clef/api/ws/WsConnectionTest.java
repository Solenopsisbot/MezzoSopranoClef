package dev.mezzo.clef.api.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WsConnectionTest {

    @Test
    void acceptKeyMatchesRfc6455Vector() {
        // From RFC 6455 §1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WsConnection.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void decodesMaskedClientTextFrame() throws Exception {
        // FIN+text(0x81), masked len=2(0x82), mask, then "hi" XOR mask.
        byte[] mask = {0x01, 0x02, 0x03, 0x04};
        byte[] frame = {
                (byte) 0x81, (byte) 0x82,
                mask[0], mask[1], mask[2], mask[3],
                (byte) ('h' ^ mask[0]), (byte) ('i' ^ mask[1])
        };
        WsConnection c = new WsConnection(new ByteArrayInputStream(frame), new ByteArrayOutputStream());
        assertEquals("hi", c.readMessage());
    }

    @Test
    void encodesUnmaskedServerTextFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WsConnection c = new WsConnection(new ByteArrayInputStream(new byte[0]), out);
        c.sendText("hi");
        // FIN+text, len=2 (unmasked), payload.
        assertArrayEquals(new byte[]{(byte) 0x81, 0x02, 'h', 'i'}, out.toByteArray());
    }

    @Test
    void reassemblesFragmentedFrames() throws Exception {
        byte[] m = {0x10, 0x20, 0x30, 0x40};
        // frame 1: text, NOT fin (0x01), "He"
        // frame 2: continuation, fin (0x80), "llo"
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x01); buf.write(0x82); buf.write(m, 0, 4);
        buf.write('H' ^ m[0]); buf.write('e' ^ m[1]);
        buf.write(0x80); buf.write(0x83); buf.write(m, 0, 4);
        buf.write('l' ^ m[0]); buf.write('l' ^ m[1]); buf.write('o' ^ m[2]);
        WsConnection c = new WsConnection(new ByteArrayInputStream(buf.toByteArray()), new ByteArrayOutputStream());
        assertEquals("Hello", c.readMessage());
    }

    @Test
    void closeFrameEndsTheMessageStream() throws Exception {
        // masked close frame (opcode 0x8), zero-length payload
        byte[] frame = {(byte) 0x88, (byte) 0x80, 0x00, 0x00, 0x00, 0x00};
        WsConnection c = new WsConnection(new ByteArrayInputStream(frame), new ByteArrayOutputStream());
        assertNull(c.readMessage());
    }

    @Test
    void pingIsAnsweredWithPongThenTextDelivered() throws Exception {
        byte[] mask = {0x05, 0x06, 0x07, 0x08};
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // ping (0x89), empty payload, masked
        buf.write(0x89); buf.write(0x80); buf.write(mask, 0, 4);
        // text "ok"
        buf.write(0x81); buf.write(0x82); buf.write(mask, 0, 4);
        buf.write('o' ^ mask[0]); buf.write('k' ^ mask[1]);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WsConnection c = new WsConnection(new ByteArrayInputStream(buf.toByteArray()), out);
        assertEquals("ok", c.readMessage());
        // server should have emitted a pong (opcode 0xA) before the text
        assertEquals((byte) 0x8A, out.toByteArray()[0]);
    }

    @Test
    void rejectsOversizedFrameBeforeAllocating() {
        // FIN+text, masked, len=127 (8-byte length) declaring ~17 MiB (> MAX_MESSAGE_BYTES).
        long huge = 17L * 1024 * 1024;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x81);
        buf.write(0xFF);
        for (int i = 7; i >= 0; i--) buf.write((int) ((huge >>> (8 * i)) & 0xFF));
        WsConnection c = new WsConnection(new ByteArrayInputStream(buf.toByteArray()), new ByteArrayOutputStream());
        assertThrows(IOException.class, c::readMessage);
    }

    @Test
    void encodesExtendedLengthForMediumPayload() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WsConnection c = new WsConnection(new ByteArrayInputStream(new byte[0]), out);
        c.sendText("x".repeat(200));
        byte[] b = out.toByteArray();
        assertEquals((byte) 0x81, b[0]);
        assertEquals(126, b[1] & 0xFF);          // 126 => 2-byte extended length
        assertEquals(200, ((b[2] & 0xFF) << 8) | (b[3] & 0xFF));
    }
}
