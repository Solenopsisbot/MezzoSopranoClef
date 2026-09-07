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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keep the GLFW window hidden in headless mode. We still let the window be <i>created</i> (so a
 * real GL context exists for on-demand screenshots), we just never show it. On a true headless
 * host with no display the window creation happens via GLFW; see README for the EGL/Xvfb note.
 *
 * Mojang field: {@code Window#handle} (the GLFW window pointer). Presenting is not touched here:
 * since 26.2 frames go through a {@code GpuSurface}, and in no-GL mode that surface is the no-op
 * one handed out by {@code NoGlDeviceBackend}.
 */
@Mixin(Window.class)
public class WindowMixin {

    @Shadow private long handle;

    /**
     * On the GLFW null platform (no display), Minecraft's window-hint of {@code GLFW_CLIENT_API =
     * OPENGL} makes GLFW try to back the window with OSMesa — which slim/headless Linux images
     * don't ship, so {@code glfwCreateWindow} fails with "OSMesa: Library not found". In no-gl mode
     * we never use a GL context, so request none. Injected right before {@code glfwCreateWindow}
     * (which since 26.2 lives in the static {@code createGlfwWindow}, after the backend has set its
     * own hints) so ours wins; only when running window-less (the macOS hidden-window path is untouched).
     */
    @Inject(method = "createGlfwWindow", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void clef$noGlContextOnNullPlatform(CallbackInfoReturnable<Long> cir) {
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
                GLFW.glfwHideWindow(handle);
            } catch (Throwable t) {
                MezzoClef.LOG.debug("Could not hide GLFW window (no window/context?): {}", t.toString());
            }
            // macOS: also drop the Dock icon / app-switcher entry (GLFW makes us a "regular" app).
            MacOsBackgroundApp.hideFromDock();
        }
    }

}
