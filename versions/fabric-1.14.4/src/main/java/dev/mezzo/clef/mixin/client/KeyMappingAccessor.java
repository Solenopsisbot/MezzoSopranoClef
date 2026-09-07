package dev.mezzo.clef.mixin.client;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Presses and releases a key binding directly.
 *
 * <p>{@code KeyMapping.setDown(boolean)} arrives in 1.15; on this release the {@code isDown} field
 * is private with only a getter, and the public statics ({@code set}/{@code click}) address a
 * binding by its bound {@code InputConstants.Key} rather than by the mapping itself. Writing the
 * field is the direct equivalent of what the newer targets call.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("isDown")
    void clef$setDown(boolean down);
}
