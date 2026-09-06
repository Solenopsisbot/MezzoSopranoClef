package dev.mezzo.clef;

import dev.mezzo.clef.config.ClefConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Common (main) entrypoint. Kept tiny on purpose: everything client-side lives in
 * {@link ClefClient}. This just owns the logger and the lazily-loaded config so any
 * subsystem can grab them without a circular dependency.
 */
public final class MezzoClef implements ModInitializer {

    public static final String MOD_ID = "mezzoclef";
    // Log4j2 rather than SLF4J deliberately: Minecraft only put SLF4J on the classpath in 1.17,
    // while the Log4j2 API has been there since long before 1.14 and is still there today. Using it
    // keeps this file — and therefore all of common/ — compiling unchanged across the whole matrix.
    // The call sites are identical either way ({} placeholders, trailing Throwable).
    public static final Logger LOG = LogManager.getLogger("MezzoSopranoClef");

    private static volatile ClefConfig config;

    @Override
    public void onInitialize() {
        LOG.info("MezzoSopranoClef common init — headless bot core loading.");
        config(); // force-load + write defaults early so the file exists for the user
    }

    /** Lazily loads (and persists defaults for) the mod config. Thread-safe. */
    public static ClefConfig config() {
        ClefConfig c = config;
        if (c == null) {
            synchronized (MezzoClef.class) {
                if (config == null) {
                    config = ClefConfig.loadOrCreate(configPath());
                }
                c = config;
            }
        }
        return c;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("mezzoclef.json");
    }

    public static Path dataDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mezzoclef");
    }
}
