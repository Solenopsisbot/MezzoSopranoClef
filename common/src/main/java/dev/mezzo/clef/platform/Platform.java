package dev.mezzo.clef.platform;

/**
 * Holds the active {@link ModPlatform}.
 *
 * <p>The loader-specific entrypoint installs one before anything else runs. On Fabric that is the
 * pre-launch entrypoint, which fires before Minecraft's classes load and before the config is first
 * read; on NeoForge it is the mod constructor.
 */
public final class Platform {

    private static volatile ModPlatform active;

    private Platform() {
    }

    /** Installs the loader implementation. Called once, before any other Clef code runs. */
    public static void set(ModPlatform platform) {
        active = platform;
    }

    /** The active platform, or a clear error if a loader forgot to install one. */
    public static ModPlatform get() {
        ModPlatform p = active;
        if (p == null) {
            throw new IllegalStateException(
                    "No ModPlatform installed — the loader entrypoint must call Platform.set() first");
        }
        return p;
    }
}
