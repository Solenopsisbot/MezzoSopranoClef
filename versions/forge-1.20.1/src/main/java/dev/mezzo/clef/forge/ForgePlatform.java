package dev.mezzo.clef.forge;

import dev.mezzo.clef.platform.ModPlatform;
import dev.mezzo.clef.version.VersionCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * {@link ModPlatform} backed by Forge's FML.
 *
 * <p>Structurally the same as {@code NeoForgePlatform} — NeoForge forked from Forge, so FMLPaths
 * and ModList survive under a different package. The whole loader-specific surface of the shared
 * code is still just these four methods.
 */
public final class ForgePlatform implements ModPlatform {

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
        return VersionCapabilities.MINECRAFT;
    }
}
