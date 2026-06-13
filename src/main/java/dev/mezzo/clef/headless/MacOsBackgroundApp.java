package dev.mezzo.clef.headless;

import dev.mezzo.clef.MezzoClef;
import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.ObjCRuntime;

/**
 * Stops the headless client showing up as a normal macOS app. GLFW makes the process a "regular"
 * app (Dock icon, app-switcher entry, can steal focus). We flip the {@code NSApplication}
 * activation policy to <i>Accessory</i> — no Dock icon, not in the app switcher, never takes focus
 * — which is what you want for a background bot. No-op on non-macOS, and any failure is swallowed
 * (purely cosmetic).
 */
public final class MacOsBackgroundApp {

    // NSApplicationActivationPolicy: 0=Regular, 1=Accessory, 2=Prohibited
    private static final long ACCESSORY = 1L;
    private static volatile boolean applied;

    public static synchronized void hideFromDock() {
        if (applied) return;
        applied = true;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return;
        try {
            long objcMsgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
            long nsApplicationClass = ObjCRuntime.objc_getClass("NSApplication");
            long sharedApplication = ObjCRuntime.sel_getUid("sharedApplication");
            long setActivationPolicy = ObjCRuntime.sel_getUid("setActivationPolicy:");

            long nsApp = JNI.invokePPP(nsApplicationClass, sharedApplication, objcMsgSend);
            if (nsApp != 0L) {
                JNI.invokePPPV(nsApp, setActivationPolicy, ACCESSORY, objcMsgSend);
                MezzoClef.LOG.info("macOS: NSApplication set to Accessory (no Dock icon / app-switcher entry).");
            }
        } catch (Throwable t) {
            MezzoClef.LOG.debug("Could not set macOS activation policy (cosmetic): {}", t.toString());
        }
    }

    private MacOsBackgroundApp() {}
}
