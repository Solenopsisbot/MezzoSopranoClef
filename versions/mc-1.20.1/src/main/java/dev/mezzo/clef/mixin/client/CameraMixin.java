package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.screenshot.ScreenshotService;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
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
 * This era: {@code setup(BlockGetter, Entity, boolean, boolean, float)}, {@code setRotation(float,float)},
 * {@code setPosition(double,double,double)}. Verify these if the inject fails to apply.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void clef$applyScreenshotOverride(BlockGetter level, Entity focused, boolean thirdPerson,
                                              boolean inverseView, float partialTick, CallbackInfo ci) {
        ScreenshotService.CameraOverride o = ScreenshotService.activeOverride();
        if (o != null) {
            setRotation(o.yaw(), o.pitch());
            setPosition(o.x(), o.y(), o.z());
        }
    }
}
