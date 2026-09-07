package dev.mezzo.clef.api.commands;

import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;

/**
 * Bulk world queries on releases that cannot support them.
 *
 * <p>{@code findBlocks}/{@code blocksIn}/{@code target}/{@code registry}/{@code nav.check} lean on
 * APIs that arrived after this release — tag lookup via {@code TagKey} (1.18+), the chunk-status
 * package split, and the packet shapes the block/damage/explosion event stream reads. Rather than
 * silently omit the commands, they are registered and return a clear error, so a client sees the
 * same surface everywhere and is told exactly why this one cannot answer.
 */
public final class WorldCommands {

    private static final String UNSUPPORTED =
            "not supported on this Minecraft version: bulk world queries need 1.20.1 or newer";

    public static void registerAll(CommandDispatcher d) {
        for (String name : new String[] {"findBlocks", "blocksIn", "target", "registry", "nav.check"}) {
            d.register(name, "unsupported on this release",
                    ctx -> { throw ApiException.badArgs(UNSUPPORTED); });
        }
    }

    private WorldCommands() {}
}
