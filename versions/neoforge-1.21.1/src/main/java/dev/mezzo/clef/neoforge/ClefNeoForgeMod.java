package dev.mezzo.clef.neoforge;

import dev.mezzo.clef.ClefClient;
import dev.mezzo.clef.ClefPreLaunch;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.platform.Platform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entrypoint. The counterpart to {@code FabricMain} + {@code FabricPreLaunch}.
 *
 * <p>NeoForge has no pre-launch phase, so the headless decisions that Fabric makes before Minecraft
 * loads happen here in the mod constructor instead — still early enough, because the mixins read
 * {@code HeadlessController} lazily on their first call rather than at class-init.
 */
@Mod(value = MezzoClef.MOD_ID, dist = Dist.CLIENT)
public final class ClefNeoForgeMod {

    public ClefNeoForgeMod(IEventBus modBus, ModContainer container) {
        Platform.set(new NeoForgePlatform());
        ClefPreLaunch.run();
        MezzoClef.init();
        new ClefClient().register(NeoForge.EVENT_BUS);
    }
}
