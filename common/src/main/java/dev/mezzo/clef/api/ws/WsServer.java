package dev.mezzo.clef.api.ws;

import dev.mezzo.clef.MezzoClef;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal multi-client WebSocket server: one acceptor thread plus one reader thread per
 * connection. Threading is fine here because each bot has only a handful of controllers and
 * all real work is bounced onto the Minecraft client thread by the command layer.
 */
public final class WsServer {

    public interface Listener {
        void onOpen(WsConnection conn);
        void onMessage(WsConnection conn, String message);
        void onClose(WsConnection conn);
    }

    /** A control plane only needs a handful of controllers; cap connections to bound resources. */
    private static final int MAX_CONNECTIONS = 32;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final Listener listener;
    private final Set<String> allowedOrigins;
    private final Set<WsConnection> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public WsServer(String host, int port, Listener listener) {
        this(host, port, listener, Set.of());
    }

    public WsServer(String host, int port, Listener listener, Set<String> allowedOrigins) {
        this.host = host;
        this.port = port;
        this.listener = listener;
        this.allowedOrigins = Set.copyOf(allowedOrigins == null ? Set.of() : allowedOrigins);
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, port));
        acceptThread = new Thread(this::acceptLoop, "clef-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        MezzoClef.LOG.info("Control plane (WebSocket) listening on ws://{}:{}", host, port);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread t = new Thread(() -> handle(socket), "clef-ws-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) MezzoClef.LOG.warn("WS accept failed: {}", e.toString());
            }
        }
    }

    private void handle(Socket socket) {
        WsConnection conn = null;
        boolean slotAcquired = false;
        try {
            if (!connectionSlots.tryAcquire()) {
                MezzoClef.LOG.warn("Refusing WS connection — at capacity ({})", MAX_CONNECTIONS);
                socket.close();
                return;
            }
            slotAcquired = true;
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            conn = new WsConnection(socket);
            if (!conn.handshake(allowedOrigins)) {
                conn.close();
                return;
            }
            socket.setSoTimeout(0);
            connections.add(conn);
            safeOnOpen(conn);
            String msg;
            while (conn.isOpen() && (msg = conn.readMessage()) != null) {
                try {
                    listener.onMessage(conn, msg);
                } catch (Exception e) {
                    MezzoClef.LOG.warn("WS message handler threw: {}", e.toString());
                }
            }
        } catch (IOException e) {
            // normal on disconnect
        } finally {
            if (conn != null) {
                connections.remove(conn);
                safeOnClose(conn);
                conn.close();
            }
            if (slotAcquired) connectionSlots.release();
        }
    }

    public void broadcast(String text) {
        for (WsConnection c : connections) {
            try {
                if (c.isOpen()) c.sendText(text);
            } catch (IOException e) {
                c.close();
            }
        }
    }

    /** Visit every open connection (used for subscription-filtered event delivery). */
    public void forEach(java.util.function.Consumer<WsConnection> action) {
        for (WsConnection c : connections) {
            if (c.isOpen()) action.accept(c);
        }
    }

    public int connectionCount() {
        return connections.size();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        for (WsConnection c : connections) c.close();
        connections.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        MezzoClef.LOG.info("Control plane stopped.");
    }

    private void safeOnOpen(WsConnection c) {
        try { listener.onOpen(c); } catch (Exception e) { MezzoClef.LOG.warn("onOpen threw", e); }
    }

    private void safeOnClose(WsConnection c) {
        try { listener.onClose(c); } catch (Exception e) { MezzoClef.LOG.warn("onClose threw", e); }
    }
}
