package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.util.TimeSource;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boots Minecraft with <b>no display server</b>.
 *
 * <p>{@code RenderSystem.initBackendSystem} runs immediately before GLFW is initialised. In
 * no-window mode we request the GLFW <i>null platform</i> there, so GLFW needs neither X11/
 * Wayland nor a GPU and the game can boot on a bare server with no {@code xvfb}. (The matching
 * "no GL context" half lives in {@code MinecraftClientNoGlMixin}, and {@code WindowMixin} makes
 * sure the window itself is created without a client API.)
 */
@Mixin(com.mojang.blaze3d.systems.RenderSystem.class)
public class RenderSystemNoGlMixin {

    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void clef$selectNullPlatform(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!HeadlessController.get().isNoWindow()) {
            return;
        }
        try {
            // GLFW_PLATFORM = null platform: no window system, no GPU, no display needed.
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_NULL);
            MezzoClef.LOG.info("[no-gl] Requested GLFW null platform (no display server required).");
        } catch (Throwable t) {
            MezzoClef.LOG.warn("[no-gl] Could not request GLFW null platform; falling back to default: {}",
                    t.toString());
        }
    }
}
