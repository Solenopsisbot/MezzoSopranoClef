package dev.mezzo.clef.mixin.client;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the current title / subtitle / action-bar text (private fields on the HUD). Since 26.2
 * these live on {@link Hud} (reachable as {@code Minecraft.gui.hud}), not on {@code Gui}.
 */
@Mixin(Hud.class)
public interface InGameHudAccessor {

    @Accessor("title")
    Component clef$getTitle();

    @Accessor("subtitle")
    Component clef$getSubtitle();

    @Accessor("overlayMessageString")
    Component clef$getOverlayMessage();
}
