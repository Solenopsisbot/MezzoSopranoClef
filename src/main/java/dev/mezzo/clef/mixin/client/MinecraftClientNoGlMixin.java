package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Boot-time efficiency tweaks that are <b>only</b> safe because no-GL mode never renders. Kept
 * separate from the render-skip mixin so this stays a clear, expandable home for "we don't draw,
 * so don't pay for draw-only data" optimizations.
 *
 * <p>Currently: force the texture-atlas <b>mipmap level to 0</b>. Mipmaps exist purely so the GPU
 * can sample textures at a distance — something we never do. Dropping them skips all mipmap
 * generation during the atlas stitch (real boot CPU) and the extra per-sprite pixel data, while
 * leaving the base texture, sprite UVs and dimensions fully intact (so any code that reads sprite
 * metadata still works). It is exactly equivalent to the vanilla "Mipmap Levels: OFF" video option.
 *
 * <p>{@code require = 0}: if a future mapping moves this call site the tweak simply does nothing
 * rather than breaking the boot.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientNoGlMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/model/BakedModelManager;<init>"
                            + "(Lnet/minecraft/client/texture/TextureManager;"
                            + "Lnet/minecraft/client/color/block/BlockColors;I)V"),
            index = 2,
            require = 0)
    private int clef$dropMipmapsWhenNoGl(int mipmapLevel) {
        if (HeadlessController.get().isNoGl() && mipmapLevel != 0) {
            MezzoClef.LOG.info("[no-gl] Dropping texture mipmaps ({} -> 0): never sampled when not rendering.", mipmapLevel);
            return 0;
        }
        return mipmapLevel;
    }
}
