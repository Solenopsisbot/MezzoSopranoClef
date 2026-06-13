package dev.mezzo.clef.api;

import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ws.WsConnection;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Everything a command handler needs: the parsed args, who sent it, the owning server (for
 * emitting events), and helpers to safely run logic on the Minecraft client thread and read
 * arguments with defaults.
 */
public final class CommandContext {

    public final ControlServer server;
    public final WsConnection origin;
    public final JsonObject args;

    public CommandContext(ControlServer server, WsConnection origin, JsonObject args) {
        this.server = server;
        this.origin = origin;
        this.args = args == null ? new JsonObject() : args;
    }

    // ---- client-thread bridging -----------------------------------------------------

    /** Runs {@code task} on the MC client thread and blocks (default 30s) for its result. */
    public <T> T onMain(Supplier<T> task) {
        return onMain(task, 30);
    }

    public <T> T onMain(Supplier<T> task, int timeoutSeconds) {
        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(cause.getMessage(), cause);
        } catch (Exception e) {
            throw new RuntimeException("Client-thread task failed: " + e.getMessage(), e);
        }
    }

    // ---- arg readers ----------------------------------------------------------------

    public String str(String key, String def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : def;
    }

    public String requireStr(String key) {
        if (!args.has(key) || args.get(key).isJsonNull())
            throw new IllegalArgumentException("missing required arg: " + key);
        return args.get(key).getAsString();
    }

    public int i(String key, int def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : def;
    }

    public double d(String key, double def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsDouble() : def;
    }

    public float f(String key, float def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsFloat() : def;
    }

    public boolean bool(String key, boolean def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsBoolean() : def;
    }

    public boolean has(String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }
}
