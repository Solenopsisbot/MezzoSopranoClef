package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.headless.MacOsBackgroundApp;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.tracy.TracyFrameCapturer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep the GLFW window hidden in headless mode. We still let the window be <i>created</i> (so a
 * real GL context exists for on-demand screenshots), we just never show it. On a true headless
 * host with no display the window creation happens via GLFW; see README for the EGL/Xvfb note.
 *
 * yarn field: {@code Window#handle} (the GLFW window pointer).
 */
@Mixin(Window.class)
public class WindowMixin {

    @Shadow private long handle;

    /**
     * On the GLFW null platform (no display), Minecraft's window-hint of {@code GLFW_CLIENT_API =
     * OPENGL} makes GLFW try to back the window with OSMesa — which slim/headless Linux images
     * don't ship, so {@code glfwCreateWindow} fails with "OSMesa: Library not found". In no-gl mode
     * we never use a GL context, so request none. Injected right before {@code glfwCreateWindow} so
     * the hint is in effect; only when running window-less (the macOS hidden-window path is untouched).
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
                GLFW.glfwHideWindow(handle);
            } catch (Throwable t) {
                MezzoClef.LOG.debug("Could not hide GLFW window (no window/context?): {}", t.toString());
            }
            // macOS: also drop the Dock icon / app-switcher entry (GLFW makes us a "regular" app).
            MacOsBackgroundApp.hideFromDock();
        }
    }

    /**
     * In no-GL mode there is no GL context (and possibly no window at all under the null
     * platform), so there is nothing to present. Skip the buffer swap entirely — otherwise GLFW
     * would error every frame on a context-less window. The loading overlay still advances
     * because its progress is driven by the render call, not the swap.
     */
    @Inject(method = "swapBuffers", at = @At("HEAD"), cancellable = true)
    private void clef$skipSwap(TracyFrameCapturer capturer, CallbackInfo ci) {
        if (HeadlessController.get().isNoGl()) {
            ci.cancel();
        }
    }
}
