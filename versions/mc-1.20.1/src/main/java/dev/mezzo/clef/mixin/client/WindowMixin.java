package dev.mezzo.clef.mixin.client;

import com.mojang.blaze3d.platform.Window;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.headless.MacOsBackgroundApp;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the GLFW window hidden in headless mode.
 *
 * <p>Pre-1.21.9 the GLFW handle field is {@code window} (it was renamed to {@code handle} later)
 * and the window is created inside the constructor rather than a static factory, so both the
 * shadow and the injection point differ from the newest targets.
 *
 * <p>On targets where the bot cannot boot GPU-free, this hidden window is what provides the real
 * GL context the client still needs; the expensive world/GUI draw is skipped regardless.
 */
@Mixin(Window.class)
public class WindowMixin {

    @Shadow private long window;

    /**
     * On the GLFW null platform (no display), Minecraft's window hint of {@code GLFW_CLIENT_API =
     * OPENGL} makes GLFW try to back the window with OSMesa — which slim/headless Linux images
     * don't ship. In no-gl mode we never use a GL context, so request none. Only meaningful on
     * targets that can actually run window-less.
     */
    @Inject(method = "<init>", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void clef$noGlContextOnNullPlatform(CallbackInfo ci) {
        if (HeadlessController.get().isNoWindow()) {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void clef$hideWindow(CallbackInfo ci) {
        HeadlessController hc = HeadlessController.get();
        // Under the GLFW null platform there is no real OS window to hide (and no Dock).
        if (hc.isNoWindow()) {
            return;
        }
        if (hc.isWindowHidden()) {
            try {
                GLFW.glfwHideWindow(window);
            } catch (Throwable t) {
                MezzoClef.LOG.debug("Could not hide GLFW window (no window/context?): {}", t.toString());
            }
            // macOS: also drop the Dock icon / app-switcher entry (GLFW makes us a "regular" app).
            MacOsBackgroundApp.hideFromDock();
        }
    }
}
