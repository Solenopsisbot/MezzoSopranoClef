package dev.mezzo.clef;

import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.headless.HeadlessController;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.util.Locale;

/**
 * Runs before Minecraft's main classes load. We use this only to decide headless mode as
 * early as possible (so the window/sound mixins behave correctly on their very first call)
 * and to nudge a few JVM properties that make running on a true headless host less noisy.
 *
 * Importantly we do NOT touch any Minecraft client classes here — that would trip the
 * classloader far too early.
 */
public final class ClefPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        ClefConfig cfg = MezzoClef.config();
        HeadlessController hc = HeadlessController.get();
        hc.setHeadless(cfg.headless);
        hc.setWindowHidden(cfg.headless);
        hc.setMuteAudio(cfg.headless);
        hc.setLoopSleepMillis(cfg.headlessLoopSleepMs);

        // The gl screenshot backend physically needs a real GL context, so it wins over no-gl.
        boolean wantsGlBackend = "gl".equalsIgnoreCase(cfg.screenshot.backend);
        boolean noGl = cfg.headless && cfg.noGl && !wantsGlBackend;
        boolean noWindow = resolveNoWindow(cfg.windowMode, noGl);
        if (noWindow && !noGl) {
            // The GLFW null platform provides no GL context; we can't drop the window while
            // still needing GL. Keep a (hidden) window instead.
            MezzoClef.LOG.warn("preLaunch: windowMode=none needs noGl, but GL is in use — keeping a hidden window.");
            noWindow = false;
        }
        hc.setNoGl(noGl);
        hc.setNoWindow(noWindow);

        // Lets LWJGL/STBI and our PNG encoding run on boxes with no display/AWT.
        if (cfg.headless && System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }

        if (cfg.headless && cfg.noGl && wantsGlBackend) {
            MezzoClef.LOG.info("preLaunch: screenshot.backend=gl requested — keeping OpenGL on "
                    + "(no-gl mode disabled). Use the default software backend to run GPU-free.");
        }
        MezzoClef.LOG.info("preLaunch: headless={} (window={}, gl={}, audio {})",
                cfg.headless,
                noWindow ? "null-platform" : (hc.isWindowHidden() ? "hidden" : "visible"),
                noGl ? "OFF (GPU-free)" : "on",
                hc.isMuteAudio() ? "muted" : "on");
    }

    /**
     * Decides whether to boot GLFW on its window-less null platform, reading the current host's
     * OS and display environment. {@code "auto"} only drops the window on a Linux host with no
     * display (the case that otherwise needs {@code xvfb}); on macOS or a desktop a hidden window
     * is cheaper and safer.
     */
    private static boolean resolveNoWindow(String windowMode, boolean noGl) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean linux = os.contains("linux") || os.contains("nux") || os.contains("nix");
        boolean mac = os.contains("mac") || os.contains("darwin");
        boolean hasDisplay = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        boolean result = decideNoWindow(windowMode, noGl, linux, mac, hasDisplay);
        String mode = windowMode == null ? "auto" : windowMode.trim().toLowerCase(Locale.ROOT);
        if (mac && !result && (mode.equals("none") || mode.equals("null") || mode.equals("headless"))) {
            MezzoClef.LOG.warn("preLaunch: windowMode=none ignored on macOS — the GLFW null platform "
                    + "stalls there (main-thread requirement); using a hidden window instead.");
        }
        return result;
    }

    /**
     * Pure window-mode decision (no environment access), so it can be unit-tested. {@code none}/
     * {@code hidden} are explicit; {@code auto} uses the null platform only when we are GPU-free
     * AND on a Linux host with no display server. The GLFW null platform stalls on macOS (it
     * requires the main thread), so it is never selected there regardless of the requested mode.
     */
    static boolean decideNoWindow(String windowMode, boolean noGl, boolean linux, boolean mac, boolean hasDisplay) {
        String mode = windowMode == null ? "auto" : windowMode.trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "none":
            case "null":
            case "headless":
                return !mac;
            case "hidden":
            case "shown":
            case "visible":
                return false;
            default: // "auto"
                return noGl && linux && !mac && !hasDisplay;
        }
    }
}
