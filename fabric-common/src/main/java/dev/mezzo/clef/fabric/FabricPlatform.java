package dev.mezzo.clef.fabric;

import dev.mezzo.clef.platform.ModPlatform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * {@link ModPlatform} backed by Fabric Loader.
 *
 * <p>Shared by every Fabric target in the matrix: these four calls have been stable across the
 * whole supported range (1.14.4 to 26.x), which is why one implementation serves all of them.
 */
public final class FabricPlatform implements ModPlatform {

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
