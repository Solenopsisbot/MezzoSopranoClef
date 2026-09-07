package dev.mezzo.clef.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

/** Enumerates active boss bars (the {@code bossBars} map has no public getter). */
@Mixin(BossHealthOverlay.class)
public interface BossBarHudAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> clef$getBossBars();
}
