package dev.mezzo.clef.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets us hot-swap the client's identity after our async auth completes. The {@code session}
 * field is otherwise private; {@link Mutable} allows writing it even though it's effectively
 * final after construction.
 *
 * NOTE: yarn field name is {@code session} as of 1.20.5+/1.21. If a future mapping renames it,
 * fix the {@code @Accessor} value here.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

    @Accessor("session")
    @Mutable
    void clef$setSession(Session session);

    @Accessor("session")
    Session clef$getSession();

    /** Lets the screenshot service temporarily redirect world rendering into its own FBO. */
    @Accessor("framebuffer")
    @Mutable
    void clef$setFramebuffer(Framebuffer framebuffer);
}
