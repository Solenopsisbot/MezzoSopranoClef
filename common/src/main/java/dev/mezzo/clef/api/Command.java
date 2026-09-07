package dev.mezzo.clef.api;

import com.google.gson.JsonElement;

/**
 * A single control-plane command. Implementations should be quick; anything that touches the
 * game world must hop onto the client thread via {@link CommandContext#onMain}.
 */
@FunctionalInterface
public interface Command {
    JsonElement run(CommandContext ctx) throws Exception;
}
