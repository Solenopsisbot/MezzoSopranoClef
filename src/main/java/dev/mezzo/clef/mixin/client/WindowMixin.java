package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.headless.MacOsBackgroundApp;
import net.minecraft.client.util.Window;
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

    @Inject(method = "<init>", at = @At("TAIL"))
    private void clef$hideWindow(CallbackInfo ci) {
        if (HeadlessController.get().isWindowHidden()) {
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
