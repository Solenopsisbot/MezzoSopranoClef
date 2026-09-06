package dev.mezzo.clef.neoforge;

import dev.mezzo.clef.platform.ModPlatform;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * {@link ModPlatform} backed by NeoForge's FML.
 *
 * <p>The NeoForge counterpart to {@code FabricPlatform} — the entire loader-specific surface of the
 * shared code is these four methods.
 */
public final class NeoForgePlatform implements ModPlatform {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get() != null && ModList.get().isLoaded(modId);
    }

    @Override
    public String minecraftVersion() {
        // NeoForge has no loader-side registry of the game version the way Fabric Loader does, so
        // ask Minecraft itself. Safe here: this is only read lazily, well after the game classes load.
        return SharedConstants.getCurrentVersion().getName();
    }
}
