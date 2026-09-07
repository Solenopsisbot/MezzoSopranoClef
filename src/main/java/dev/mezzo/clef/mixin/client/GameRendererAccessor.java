package dev.mezzo.clef.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the screenshot service temporarily redirect world rendering into its own render target.
 * Since 26.2 the main render target is owned by {@link GameRenderer} (private final field
 * {@code mainRenderTarget}); {@link Mutable} lets us swap it for one render.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("mainRenderTarget")
    @Mutable
    void clef$setMainRenderTarget(RenderTarget target);
}
