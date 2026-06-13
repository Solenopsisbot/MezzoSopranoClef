package dev.mezzo.clef.mixin.client;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the current title / subtitle / action-bar text (private fields on the HUD). */
@Mixin(InGameHud.class)
public interface InGameHudAccessor {

    @Accessor("title")
    Text clef$getTitle();

    @Accessor("subtitle")
    Text clef$getSubtitle();

    @Accessor("overlayMessage")
    Text clef$getOverlayMessage();
}
