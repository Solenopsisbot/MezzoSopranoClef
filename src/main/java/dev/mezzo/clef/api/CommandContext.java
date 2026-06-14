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
        if (!has(key)) return def;
        try {
            return args.get(key).getAsString();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a string");
        }
    }

    public String requireStr(String key) {
        if (!has(key)) throw ApiException.badArgs("missing required arg: " + key);
        try {
            return args.get(key).getAsString();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a string");
        }
    }

    /** Required integer arg. Throws {@link ApiException} ({@code BAD_ARGS}) if missing or non-numeric. */
    public int requireInt(String key) {
        if (!has(key)) throw ApiException.badArgs("missing required arg: " + key);
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be an integer");
        }
    }

    /** Required double arg. Throws {@link ApiException} ({@code BAD_ARGS}) if missing or non-numeric. */
    public double requireDouble(String key) {
        if (!has(key)) throw ApiException.badArgs("missing required arg: " + key);
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a number");
        }
    }

    /** Required float arg. Throws {@link ApiException} ({@code BAD_ARGS}) if missing or non-numeric. */
    public float requireFloat(String key) {
        if (!has(key)) throw ApiException.badArgs("missing required arg: " + key);
        try {
            return args.get(key).getAsFloat();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a number");
        }
    }

    public int i(String key, int def) {
        if (!has(key)) return def;
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be an integer");
        }
    }

    public double d(String key, double def) {
        if (!has(key)) return def;
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a number");
        }
    }

    public float f(String key, float def) {
        if (!has(key)) return def;
        try {
            return args.get(key).getAsFloat();
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a number");
        }
    }

    public boolean bool(String key, boolean def) {
        if (!has(key)) return def;
        try {
            if (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isBoolean()) {
                throw ApiException.badArgs("arg '" + key + "' must be a boolean");
            }
            return args.get(key).getAsBoolean();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badArgs("arg '" + key + "' must be a boolean");
        }
    }

    public boolean has(String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }
}
