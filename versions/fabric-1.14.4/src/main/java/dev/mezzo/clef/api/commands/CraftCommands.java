package dev.mezzo.clef.api.commands;

import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;

/**
 * Crafting commands on releases without the recipe-display API (pre-1.21.4).
 *
 * <p>The commands are still registered so the control-plane surface is the same everywhere — a
 * client asking {@code schema} sees the same names — but each returns a clear error naming the
 * requirement rather than silently missing.
 */
public final class CraftCommands {

    private static final String UNSUPPORTED =
            "not supported on this Minecraft version: crafting needs 1.21.4 or newer, "
            + "whose recipe-display API this build does not have";

    public static void registerAll(CommandDispatcher d) {
        d.register("craft", "unsupported on this release {item, count?=1, all?}",
                ctx -> { throw ApiException.badArgs(UNSUPPORTED); });
        d.register("recipes", "unsupported on this release {item}",
                ctx -> { throw ApiException.badArgs(UNSUPPORTED); });
        d.register("craftable", "unsupported on this release",
                ctx -> { throw ApiException.badArgs(UNSUPPORTED); });
    }

    private CraftCommands() {}
}
