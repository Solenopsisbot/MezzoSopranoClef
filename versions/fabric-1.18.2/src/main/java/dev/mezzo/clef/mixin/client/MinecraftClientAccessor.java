package dev.mezzo.clef.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets us hot-swap the client's identity after our async auth completes. The {@code user}
 * field is otherwise private; {@link Mutable} allows writing it even though it's effectively
 * final after construction.
 *
 * NOTE: Mojang field name is {@code user} (26.x). If a future release renames it, fix the
 * {@code @Accessor} value here.
 */
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {

    @Accessor("user")
    @Mutable
    void clef$setSession(User session);

    @Accessor("user")
    User clef$getSession();

    /** Lets the screenshot service temporarily redirect world rendering into its own target. */
    @Accessor("mainRenderTarget")
    @Mutable
    void clef$setFramebuffer(RenderTarget target);
}
