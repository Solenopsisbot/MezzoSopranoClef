package dev.mezzo.clef.api.ws;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single RFC 6455 WebSocket connection, implemented by hand so we pull in zero extra
 * dependencies. Supports the only things a JSON control plane needs: the opening HTTP
 * handshake, masked text frames (client -> server), fragmentation reassembly, ping/pong,
 * and clean close. Server -> client frames are sent unmasked per spec.
 */
public final class WsConnection implements AutoCloseable {

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final AtomicLong IDS = new AtomicLong();

    /** Hard cap on a single inbound message (sum of fragments). Control traffic is tiny; this
     *  only exists to stop a hostile/buggy client from OOMing us with a giant declared length. */
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    static final int MAX_HANDSHAKE_BYTES = 16 * 1024;

    private final long id = IDS.incrementAndGet();
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean open = false;

    /** Free-form per-connection attributes (e.g. whether it passed auth). */
    public final Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

    public WsConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Stream-based constructor for tests — exercises the frame codec without a real socket. */
    WsConnection(InputStream in, OutputStream out) {
        this.socket = null;
        this.in = in;
        this.out = out;
        this.open = true;
    }

    /** RFC 6455 server accept value for a client key. Exposed for tests. */
    static String acceptKey(String secWebSocketKey) {
        return sha1Base64(secWebSocketKey + MAGIC);
    }

    public long id() {
        return id;
    }

    public boolean isOpen() {
        return open && (socket == null || !socket.isClosed());
    }

    /** Performs the HTTP Upgrade handshake. @return true if it was a valid WS upgrade. */
    public boolean handshake() throws IOException {
        return handshake(Set.of());
    }

    /**
     * Performs the HTTP Upgrade handshake. Browser clients send an Origin header; when present it
     * must match the configured allow-list. Non-browser clients commonly omit Origin and are
     * allowed so existing CLI/probe clients still work.
     */
    public boolean handshake(Set<String> allowedOrigins) throws IOException {
        Map<String, String> headers = readHttpHeaders();
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            writeHttp("HTTP/1.1 400 Bad Request", "Expected a WebSocket upgrade");
            return false;
        }
        String origin = headers.get("origin");
        if (origin != null && (allowedOrigins == null || !allowedOrigins.contains(origin))) {
            writeHttp("HTTP/1.1 403 Forbidden", "Origin not allowed");
            return false;
        }
        String accept = acceptKey(key);
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        open = true;
        return true;
    }

    /** Blocks until a full text message arrives. Returns null when the peer closes. */
    public String readMessage() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int messageOpcode = -1;

        while (true) {
            int b0 = in.read();
            if (b0 == -1) return null;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;

            int b1 = in.read();
            if (b1 == -1) return null;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }

            // Reject absurd/hostile frame sizes BEFORE allocating — a crafted 8-byte length
            // would otherwise OOM the JVM (or, via the (int) cast, allocate a truncated buffer).
            if (len < 0 || len > MAX_MESSAGE_BYTES) {
                throw new IOException("WebSocket frame length " + len + " exceeds limit " + MAX_MESSAGE_BYTES);
            }
            if (payload.size() + len > MAX_MESSAGE_BYTES) {
                throw new IOException("WebSocket message exceeds limit " + MAX_MESSAGE_BYTES);
            }

            byte[] mask = new byte[4];
            if (masked) readFully(mask, 4);

            byte[] data = new byte[(int) len];
            readFully(data, (int) len);
            if (masked) {
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case 0x0 -> payload.write(data, 0, data.length);            // continuation
                case 0x1 -> { messageOpcode = 0x1; payload.write(data, 0, data.length); } // text
                case 0x2 -> { messageOpcode = 0x2; payload.write(data, 0, data.length); } // binary
                case 0x8 -> { sendClose(); return null; }                    // close
                case 0x9 -> { sendFrame(0xA, data); continue; }              // ping -> pong
                case 0xA -> { continue; }                                    // pong
                default  -> { /* ignore unknown */ continue; }
            }

            if (fin) {
                if (messageOpcode == 0x2) {
                    // Binary not used by the control protocol; surface as empty + ignore.
                    payload.reset();
                    continue;
                }
                return payload.toString(StandardCharsets.UTF_8);
            }
        }
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendClose() {
        try {
            sendFrame(0x8, new byte[0]);
        } catch (IOException ignored) {
        } finally {
            open = false;
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x80 | (opcode & 0x0F)); // FIN + opcode
            int len = payload.length;
            if (len < 126) {
                f.write(len);
            } else if (len < 0x10000) {
                f.write(126);
                f.write((len >>> 8) & 0xFF);
                f.write(len & 0xFF);
            } else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write((int) ((long) len >>> (8 * i)) & 0xFF);
            }
            f.write(payload, 0, payload.length);
            out.write(f.toByteArray());
            out.flush();
        }
    }

    @Override
    public void close() {
        sendClose();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- low-level helpers ----------------------------------------------------------

    private Map<String, String> readHttpHeaders() throws IOException {
        Map<String, String> headers = new TreeMap<>();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1, cur;
        boolean firstLine = true;
        int total = 0;
        while ((cur = in.read()) != -1) {
            if (++total > MAX_HANDSHAKE_BYTES) {
                throw new IOException("WebSocket handshake exceeds " + MAX_HANDSHAKE_BYTES + " bytes");
            }
            if (prev == '\r' && cur == '\n') {
                String s = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (s.isEmpty()) break; // end of headers
                if (firstLine) {
                    firstLine = false; // request line, ignore
                } else {
                    int colon = s.indexOf(':');
                    if (colon > 0) {
                        headers.put(s.substring(0, colon).trim().toLowerCase(),
                                s.substring(colon + 1).trim());
                    }
                }
            } else if (cur != '\r') {
                if (cur != '\n') line.write(cur);
            }
            prev = cur;
        }
        return headers;
    }

    private void writeHttp(String status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = status + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private int readByte() throws IOException {
        int v = in.read();
        if (v == -1) throw new IOException("EOF mid-frame");
        return v;
    }

    private void readFully(byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r == -1) throw new IOException("EOF mid-frame");
            off += r;
        }
    }

    private static String sha1Base64(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
