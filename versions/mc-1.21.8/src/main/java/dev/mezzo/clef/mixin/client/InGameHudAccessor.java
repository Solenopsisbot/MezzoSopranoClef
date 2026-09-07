package dev.mezzo.clef.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the current title / subtitle / action-bar text (private fields on the HUD). On 1.21.x these
 * live directly on {@link Gui}; 26.2 later moved them onto {@code Gui.hud}.
 */
@Mixin(Gui.class)
public interface InGameHudAccessor {

    @Accessor("title")
    Component clef$getTitle();

    @Accessor("subtitle")
    Component clef$getSubtitle();

    @Accessor("overlayMessageString")
    Component clef$getOverlayMessage();
}
