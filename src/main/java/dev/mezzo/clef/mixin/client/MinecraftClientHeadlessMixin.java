package dev.mezzo.clef.mixin.client;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The heart of "headless": skip the expensive world/GUI <b>draw</b> while keeping everything
 * else (task pumping via {@code runTasks()}, ticking, the frame flip) alive — cancelling the
 * whole {@code render(boolean)} freezes the client and hangs control commands.
 *
 * <p>We use {@link WrapWithCondition} (not {@code @Redirect}) on the {@code GameRenderer.render}
 * call so we coexist with other mods that touch the same call. We only go dark once actually in
 * a world with no loading overlay (during startup/menus the render thread must keep drawing so
 * the blaze3d GPU command queue drains, else the loading screen deadlocks).
 *
 * <p>Pacing: with the draw skipped, the loop would otherwise spin as fast as the frame limiter
 * allows. We sleep {@code loopSleepMillis} at the tail of each render to keep idle in-world CPU
 * near zero while staying responsive to the 20 TPS tick + network.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientHeadlessMixin {

    @WrapWithCondition(
            method = "render(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;Z)V"))
    private boolean clef$skipWorldDraw(GameRenderer renderer, RenderTickCounter counter, boolean tick) {
        if (clef$goDark()) {
            HeadlessController.get().noteSkippedFrame();
            return false; // skip drawing
        }
        return true; // draw normally
    }

    @Inject(method = "render(Z)V", at = @At("TAIL"))
    private void clef$paceLoop(boolean tick, CallbackInfo ci) {
        // Only pace once in-world (where we skip the draw). On menus/loading we leave Minecraft's
        // own frame limiter alone — it keeps idle CPU low and behaves correctly.
        if (clef$goDark()) {
            int sleep = HeadlessController.get().loopSleepMillis();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Unique
    private boolean clef$goDark() {
        HeadlessController hc = HeadlessController.get();
        if (!hc.isHeadless()) return false;
        MinecraftClient mc = (MinecraftClient) (Object) this;
        return mc.world != null && mc.getOverlay() == null;
    }
}
