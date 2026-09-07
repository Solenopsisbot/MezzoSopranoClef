package dev.mezzo.clef.mixin.client;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the current title / subtitle / action-bar text (private fields on the HUD).
 *
 * <p>On this release they are plain {@link String}s — the HUD only starts holding {@code Component}
 * in 1.16. The accessor return type has to match the real field type exactly or Mixin fails to
 * apply at class-load time, which is a runtime failure the compiler cannot see.
 */
@Mixin(Gui.class)
public interface InGameHudAccessor {

    @Accessor("title")
    String clef$getTitle();

    @Accessor("subtitle")
    String clef$getSubtitle();

    @Accessor("overlayMessageString")
    String clef$getOverlayMessage();
}
