package dev.mezzo.clef.mixin.client;

import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the screenshot service temporarily spoof the framebuffer dimensions so the world
 * projection (aspect ratio / FOV math reads these) matches the requested capture resolution,
 * then restores them. yarn fields: {@code framebufferWidth}, {@code framebufferHeight}.
 */
@Mixin(Window.class)
public interface WindowAccessor {

    @Accessor("framebufferWidth")
    @Mutable
    void clef$setFramebufferWidth(int width);

    @Accessor("framebufferHeight")
    @Mutable
    void clef$setFramebufferHeight(int height);
}
