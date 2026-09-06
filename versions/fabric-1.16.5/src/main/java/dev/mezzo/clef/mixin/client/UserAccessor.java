package dev.mezzo.clef.mixin.client;

import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the account type off {@link User}. The field is private with no getter on this release —
 * {@code User.getType()} only appears in 1.17 — so {@code auth.status} would otherwise have no way
 * to report the same {@code type} value the rest of the matrix does.
 */
@Mixin(User.class)
public interface UserAccessor {

    @Accessor("type")
    User.Type clef$getType();
}
