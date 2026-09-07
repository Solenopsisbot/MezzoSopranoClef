package dev.mezzo.clef.mixin.client;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The heart of "headless": skip the expensive world/GUI <b>draw</b> while keeping everything
 * else (task pumping via {@code runAllTasks()}, ticking, the surface present) alive — cancelling
 * the whole {@code runTick(boolean)} freezes the client and hangs control commands.
 *
 * <p>Since 26.2 the loop body is {@code runTick(boolean)} (tick + tasks + pacing) and the frame
 * itself is {@code renderFrame(boolean)}, which first <i>extracts</i> render state from the world
 * ({@code GameRenderer.extract}) and then <i>draws</i> it ({@code GameRenderer.render}). We skip
 * both halves together — extraction is pure CPU work whose only consumer is the draw.
 *
 * <p>We use {@link WrapWithCondition} (not {@code @Redirect}) on those calls so we coexist with
 * other mods that touch the same call sites. We only go dark once actually in a world with no
 * loading overlay (during startup/menus the render thread must keep drawing so the blaze3d GPU
 * command queue drains, else the loading screen deadlocks).
 *
 * <p>Pacing: with the draw skipped, the loop would otherwise spin as fast as the frame limiter
 * allows. We sleep {@code loopSleepMillis} at the tail of each render to keep idle in-world CPU
 * near zero while staying responsive to the 20 TPS tick + network.
 */
@Mixin(Minecraft.class)
public class MinecraftClientHeadlessMixin {

    @WrapWithCondition(
            method = "renderFrame(Z)V",
            at = {
                    @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"),
                    @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V")
            })
    private boolean clef$skipWorldDraw(GameRenderer renderer, DeltaTracker counter, boolean tick) {
        if (clef$goDark()) {
            // Counted twice per frame (extract + render) — halve it so the stat stays "frames".
            if (clef$skipToggle = !clef$skipToggle) HeadlessController.get().noteSkippedFrame();
            return false; // skip extracting/drawing
        }
        return true; // draw normally
    }

    @Inject(method = "runTick(Z)V", at = @At("TAIL"))
    private void clef$paceLoop(boolean tick, CallbackInfo ci) {
        HeadlessController hc = HeadlessController.get();
        // Pace when we skipped the draw (the normal case), and also during the no-GL loading
        // screen: there the overlay still draws but the GLFW null platform's frame limiter does
        // not block, so without this the render thread would busy-spin at 100%. A small sleep only
        // slows the loading fade slightly — the reload runs on background threads regardless.
        if (!clef$goDark() && !(hc.isHeadless() && hc.isNoGl())) {
            return;
        }
        int sleep = hc.loopSleepMillis();
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Unique
    private boolean clef$skipToggle;

    @Unique
    private boolean clef$goDark() {
        HeadlessController hc = HeadlessController.get();
        if (!hc.isHeadless()) return false;
        Minecraft mc = (Minecraft) (Object) this;
        // Always let the loading overlay draw: the resource reload advances and the overlay
        // removes itself from inside its own render method, so skipping it deadlocks startup.
        if (mc.gui.overlay() != null) return false;
        // No-GL: nothing is ever presented, so skip every non-loading frame (title/menu AND
        // in-world) — this also avoids needless menu/world geometry work on the CPU.
        if (hc.isNoGl()) return true;
        // Legacy headless (real GL + hidden window): keep menus drawing; only skip the in-world draw.
        return mc.level != null;
    }
}
