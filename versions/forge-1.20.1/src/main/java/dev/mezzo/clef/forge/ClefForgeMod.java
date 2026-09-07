package dev.mezzo.clef.forge;

import dev.mezzo.clef.ClefClient;
import dev.mezzo.clef.ClefPreLaunch;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.platform.Platform;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entrypoint. Like NeoForge, Forge constructs mods during loading — before
 * {@code Minecraft.getInstance()} exists — so the bot core is started lazily from the first client
 * tick rather than here. This constructor only installs the platform and registers listeners.
 */
@Mod(MezzoClef.MOD_ID)
public final class ClefForgeMod {

    public ClefForgeMod() {
        Platform.set(new ForgePlatform());
        ClefPreLaunch.run();
        MezzoClef.init();
        new ClefClient().register(MinecraftForge.EVENT_BUS);
    }
}
