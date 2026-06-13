package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.screenshot.ScreenshotService;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The camera is rebuilt from the focused entity every frame inside {@code Camera.update}, so a
 * "look from here at this angle" screenshot can't just set the camera beforehand — it would be
 * overwritten. Instead, when {@link ScreenshotService} has raised an override, we re-apply the
 * requested position/rotation at the TAIL of update so it wins for that single render.
 *
 * yarn (1.21): {@code update(BlockView, Entity, boolean, boolean, float)},
 * {@code setRotation(float,float)}, {@code setPos(double,double,double)}. Verify these against
 * the exact 1.21.8 mappings if the inject fails to apply.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void clef$applyScreenshotOverride(BlockView area, Entity focusedEntity,
                                              boolean thirdPerson, boolean inverseView,
                                              float tickDelta, CallbackInfo ci) {
        ScreenshotService.CameraOverride o = ScreenshotService.activeOverride();
        if (o != null) {
            setRotation(o.yaw(), o.pitch());
            setPos(o.x(), o.y(), o.z());
        }
    }
}
