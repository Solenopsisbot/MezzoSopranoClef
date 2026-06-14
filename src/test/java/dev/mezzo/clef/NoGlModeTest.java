package dev.mezzo.clef;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the headless window-mode decision that selects the GLFW null platform (no display
 * server / no xvfb). The decision is pure so we can exercise every host combination here without
 * launching Minecraft. Signature: decideNoWindow(windowMode, noGl, linux, mac, hasDisplay).
 */
class NoGlModeTest {

    @Test
    void explicitNoneDropsWindowExceptOnMacOs() {
        assertTrue(ClefPreLaunch.decideNoWindow("none", true, true, false, true));
        assertTrue(ClefPreLaunch.decideNoWindow("null", false, false, false, true));
        assertTrue(ClefPreLaunch.decideNoWindow("headless", true, false, false, false));
        // macOS stalls on the null platform — never select it there, even when asked.
        assertFalse(ClefPreLaunch.decideNoWindow("none", true, false, true, false));
    }

    @Test
    void explicitHiddenAlwaysKeepsAWindow() {
        assertFalse(ClefPreLaunch.decideNoWindow("hidden", true, true, false, false));
        assertFalse(ClefPreLaunch.decideNoWindow("visible", true, true, false, false));
    }

    @Test
    void autoDropsWindowOnlyOnHeadlessLinuxWhenGpuFree() {
        // The target case: Linux server, no display, GPU-free -> null platform (no xvfb needed).
        assertTrue(ClefPreLaunch.decideNoWindow("auto", true, true, false, false));
    }

    @Test
    void autoKeepsWindowWhenADisplayIsPresent() {
        assertFalse(ClefPreLaunch.decideNoWindow("auto", true, true, false, true));
    }

    @Test
    void autoKeepsWindowOffLinux() {
        // Windows: a hidden window is cheaper + safer than the null platform.
        assertFalse(ClefPreLaunch.decideNoWindow("auto", true, false, false, false));
        // macOS: never the null platform.
        assertFalse(ClefPreLaunch.decideNoWindow("auto", true, false, true, false));
    }

    @Test
    void autoNeverDropsWindowWhenGlIsStillNeeded() {
        // No GL context => the null platform can't satisfy it, so we must keep a window.
        assertFalse(ClefPreLaunch.decideNoWindow("auto", false, true, false, false));
    }

    @Test
    void nullModeStringDefaultsToAuto() {
        assertFalse(ClefPreLaunch.decideNoWindow(null, true, false, false, false));
        assertTrue(ClefPreLaunch.decideNoWindow(null, true, true, false, false));
    }
}
