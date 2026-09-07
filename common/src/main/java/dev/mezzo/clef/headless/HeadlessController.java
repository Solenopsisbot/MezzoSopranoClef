package dev.mezzo.clef.headless;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Central, mixin-readable switch for headless behaviour. Kept dependency-free and tiny
 * so it can be touched from the earliest preLaunch phase and from render-thread mixins
 * without dragging in the rest of the mod.
 *
 * <p>When {@link #isHeadless()} is true the client's per-frame render is skipped entirely
 * (see {@code MinecraftClientHeadlessMixin}). Screenshots are produced out-of-band by the
 * {@code ScreenshotService}, which schedules its own one-shot offscreen render on the main
 * thread, so it does NOT depend on the normal render loop running.
 */
public final class HeadlessController {

    private static final HeadlessController INSTANCE = new HeadlessController();

    private volatile boolean headless = true;
    private volatile boolean windowHidden = true;
    private volatile boolean muteAudio = true;

    /**
     * When true, Minecraft boots with a stub {@code GpuDevice} and never creates or touches an
     * OpenGL context (see {@code RenderSystemNoGlMixin}). The default {@code software} screenshot
     * backend needs no GL, so the bot runs with zero GPU work. Set by {@code ClefPreLaunch} from
     * config; only meaningful while {@link #isHeadless()}.
     */
    private volatile boolean noGl = false;

    /**
     * When true, GLFW is initialised on its <i>null platform</i> — no window, no display server,
     * no GPU — so the client boots on a bare host with no {@code xvfb}. Requires {@link #noGl}
     * (the null platform provides no GL context).
     */
    private volatile boolean noWindow = false;

    /**
     * When rendering is skipped, the client's main loop would otherwise busy-spin at 100% CPU
     * (rendering/vsync is what normally paces it). We sleep this many ms per loop instead — low
     * enough to stay responsive to the 20 TPS tick + network, high enough to idle near 0% CPU.
     */
    private volatile int loopSleepMillis = 5;

    /** Frames the normal loop has skipped — handy telemetry over the control plane. */
    private final AtomicLong skippedFrames = new AtomicLong();

    /** Sound plays we suppressed while muted — telemetry for the footprint-hygiene sound disable. */
    private final AtomicLong soundsSuppressed = new AtomicLong();

    /**
     * Kill switch for the muted-sound short-circuit (default on). Lets us toggle it at runtime to
     * A/B the cost without otherwise changing the headless setup: launch with
     * {@code -Dmezzoclef.disable.sound=false} or flip it via the {@code optimize} control command.
     */
    private volatile boolean disableSound =
            !"false".equalsIgnoreCase(System.getProperty("mezzoclef.disable.sound"));

    public static HeadlessController get() {
        return INSTANCE;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public boolean isWindowHidden() {
        return windowHidden;
    }

    public void setWindowHidden(boolean windowHidden) {
        this.windowHidden = windowHidden;
    }

    public boolean isMuteAudio() {
        return muteAudio;
    }

    public void setMuteAudio(boolean muteAudio) {
        this.muteAudio = muteAudio;
    }

    public boolean isNoGl() {
        return noGl;
    }

    public void setNoGl(boolean noGl) {
        this.noGl = noGl;
    }

    public boolean isNoWindow() {
        return noWindow;
    }

    public void setNoWindow(boolean noWindow) {
        this.noWindow = noWindow;
    }

    public int loopSleepMillis() {
        return loopSleepMillis;
    }

    public void setLoopSleepMillis(int loopSleepMillis) {
        this.loopSleepMillis = Math.max(0, loopSleepMillis);
    }

    public long noteSkippedFrame() {
        return skippedFrames.incrementAndGet();
    }

    public long skippedFrames() {
        return skippedFrames.get();
    }

    public boolean isDisableSound() {
        return disableSound;
    }

    public void setDisableSound(boolean disableSound) {
        this.disableSound = disableSound;
    }

    public long noteSoundSuppressed() {
        return soundsSuppressed.incrementAndGet();
    }

    public long soundsSuppressed() {
        return soundsSuppressed.get();
    }

    private HeadlessController() {}
}
