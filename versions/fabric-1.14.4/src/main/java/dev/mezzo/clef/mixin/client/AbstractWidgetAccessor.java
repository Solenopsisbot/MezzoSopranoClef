package dev.mezzo.clef.mixin.client;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a widget's height. On this release {@code height} is a protected field with no getter
 * ({@code getHeight()} arrives in 1.16), and the UI commands need it to click a widget's centre.
 */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {

    @Accessor("height")
    int clef$getHeight();
}
