package dev.mezzo.clef.platform;

import java.nio.file.Path;

/**
 * The handful of things the bot needs from its mod loader.
 *
 * <p>Everything else in {@code common/} is loader-agnostic already; this is the whole seam. Fabric
 * answers these through {@code FabricLoader}, NeoForge through {@code FMLPaths}/{@code ModList}.
 * Keeping it this small is deliberate — a loader port should be a new implementation of four
 * methods plus that loader's entrypoints, not a fork of the shared code.
 */
public interface ModPlatform {

    /** Directory for {@code mezzoclef.json} (Fabric {@code config/}, NeoForge {@code config/}). */
    Path configDir();

    /** The game directory, i.e. the run directory Minecraft was launched in. */
    Path gameDir();

    /** True when another mod with this id is loaded. Used to detect ViaFabricPlus and Baritone. */
    boolean isModLoaded(String modId);

    /** The Minecraft version this client was built for — what {@code "native"} means to the API. */
    String minecraftVersion();
}
