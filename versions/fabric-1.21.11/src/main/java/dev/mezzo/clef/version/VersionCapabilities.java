package dev.mezzo.clef.version;

/**
 * What this particular Minecraft target can do. Every version module declares its own copy; the
 * shared code in {@code common/} reads these instead of assuming the newest release's abilities.
 *
 * <p>The control plane, events, screenshots (software backend), Baritone bridge and headless
 * frame-skipping work on every target. GPU-free booting does not: it needs a Blaze3D {@code
 * GpuDevice} to substitute, which only exists — and only in a stable enough shape — on newer
 * releases. Where it is unavailable the bot still runs headless behind a hidden window.
 */
public final class VersionCapabilities {

    /** Minecraft release this build targets. */
    public static final String MINECRAFT = "1.21.11";

    /** True when a stub GPU device can replace the real backend, so no OpenGL context is created. */
    public static final boolean SUPPORTS_NO_GL = true;

    private VersionCapabilities() {}
}
