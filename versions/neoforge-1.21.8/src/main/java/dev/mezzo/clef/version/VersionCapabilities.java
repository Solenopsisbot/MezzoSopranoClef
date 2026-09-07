package dev.mezzo.clef.version;

/**
 * What this particular Minecraft target can do — the NeoForge 1.21.8 build.
 *
 * <p>Matches the Fabric 1.21.8 target, GPU-free booting included. That stub is not *shared* with
 * Fabric — NeoForge patches Blaze3D with its own extension interfaces, so the device has to satisfy
 * more than vanilla's — but it is implemented here, so this target boots with no OpenGL context at
 * all rather than behind a hidden window.
 */
public final class VersionCapabilities {

    /** Minecraft release this build targets. */
    public static final String MINECRAFT = "1.21.8";

    /** True when a stub GPU device can replace the real backend, so no OpenGL context is created. */
    public static final boolean SUPPORTS_NO_GL = true;

    private VersionCapabilities() {}
}
