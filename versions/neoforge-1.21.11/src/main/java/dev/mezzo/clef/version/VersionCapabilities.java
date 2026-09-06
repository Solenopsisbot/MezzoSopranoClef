package dev.mezzo.clef.version;

/**
 * What this particular Minecraft target can do — the NeoForge 1.21.8 build.
 *
 * <p>Identical to the Fabric 1.21.8 target except for GPU-free booting. NeoForge patches Blaze3D
 * with its own extension interfaces ({@code GpuDeviceExtension}, plus extra methods on
 * {@code RenderPass} and {@code CommandEncoder}), so the stub device written against vanilla's
 * interfaces does not satisfy them and is not shareable between the two loaders. Until a NeoForge
 * stub exists this target runs headless behind a hidden window, exactly like the 1.21.4-and-older
 * Fabric targets do.
 */
public final class VersionCapabilities {

    /** Minecraft release this build targets. */
    public static final String MINECRAFT = "1.21.11";

    /** True when a stub GPU device can replace the real backend, so no OpenGL context is created. */
    public static final boolean SUPPORTS_NO_GL = false;

    private VersionCapabilities() {}
}
