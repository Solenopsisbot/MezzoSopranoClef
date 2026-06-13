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
     * When rendering is skipped, the client's main loop would otherwise busy-spin at 100% CPU
     * (rendering/vsync is what normally paces it). We sleep this many ms per loop instead — low
     * enough to stay responsive to the 20 TPS tick + network, high enough to idle near 0% CPU.
     */
    private volatile int loopSleepMillis = 5;

    /** Frames the normal loop has skipped — handy telemetry over the control plane. */
    private final AtomicLong skippedFrames = new AtomicLong();

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

    private HeadlessController() {}
}
