package dev.mezzo.clef.neoforge;

import dev.mezzo.clef.platform.ModPlatform;
import dev.mezzo.clef.version.VersionCapabilities;
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
        // Read from the module's own capabilities rather than SharedConstants: the WorldVersion
        // accessor is getName() before 1.21.9 and name() after, so asking Minecraft would make this
        // file differ per release for no benefit. The constant is set by the build for this target.
        return VersionCapabilities.MINECRAFT;
    }
}
