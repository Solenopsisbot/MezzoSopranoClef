package dev.mezzo.clef.fabric;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.platform.Platform;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric's main entrypoint. {@link FabricPreLaunch} has normally installed the platform already;
 * setting it again here is harmless and keeps this working if the pre-launch entry is ever removed.
 */
public final class FabricMain implements ModInitializer {

    @Override
    public void onInitialize() {
        Platform.set(new FabricPlatform());
        MezzoClef.init();
    }
}
