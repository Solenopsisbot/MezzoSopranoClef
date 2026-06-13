package dev.mezzo.clef;

import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.headless.HeadlessController;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

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

        // Lets LWJGL/STBI and our PNG encoding run on boxes with no display/AWT.
        if (cfg.headless && System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }

        MezzoClef.LOG.info("preLaunch: headless={} (window {}, audio {})",
                cfg.headless,
                hc.isWindowHidden() ? "hidden" : "visible",
                hc.isMuteAudio() ? "muted" : "on");
    }
}
