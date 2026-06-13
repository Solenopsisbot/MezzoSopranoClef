package dev.mezzo.clef.nav;

/**
 * Pathfinding abstraction so the rest of the bot never hard-depends on Baritone. The default
 * implementation ({@link BaritoneNavigator}) bridges to Baritone reflectively if it's on the
 * classpath, and degrades to clear "not available" errors otherwise.
 */
public interface Navigator {

    /** True if a real pathfinding backend (Baritone) was found at runtime. */
    boolean isAvailable();

    /** Human-readable backend name for status reporting. */
    String backend();

    /** Walk to an exact block. */
    void goTo(int x, int y, int z);

    /** Walk to an X/Z column at any Y. */
    void goToXZ(int x, int z);

    /**
     * Run a raw Baritone command (the full Baritone command set — {@code mine diamond_ore},
     * {@code follow player}, {@code goto x y z}, {@code build}, etc.) when Baritone is installed.
     */
    void runCommand(String command);

    /** Cancel all current pathing. */
    void stop();

    /** True if currently pathing somewhere. */
    boolean isActive();
}
