package dev.mezzo.clef.fabric;

import dev.mezzo.clef.ClefPreLaunch;
import dev.mezzo.clef.platform.Platform;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric's pre-launch entrypoint.
 *
 * <p>This is the earliest hook Fabric offers, and the first thing it does is install the platform —
 * the shared pre-launch work reads the config, which needs {@code configDir()}. Everything after
 * that lives in {@link ClefPreLaunch}, which names no loader types.
 */
public final class FabricPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        Platform.set(new FabricPlatform());
        ClefPreLaunch.run();
    }
}
