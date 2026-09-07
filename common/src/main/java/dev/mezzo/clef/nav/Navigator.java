package dev.mezzo.clef.nav;

/**
 * Pathfinding abstraction so the rest of the bot never hard-depends on Baritone. The default
 * implementation ({@link BaritoneNavigator}) bridges to Baritone reflectively if it's on the
 * classpath, and degrades to clear "not available" errors otherwise.
 */
public interface Navigator {

    /** Where the bot was last told to walk. {@code y} is null for an X/Z-only goal. */
    record Goal(int x, Integer y, int z, int reach) {

        /** True if {@code (px,py,pz)} satisfies this goal, ignoring Y for an X/Z goal. */
        public boolean satisfiedBy(double px, double py, double pz) {
            int slack = Math.max(1, reach);
            if (Math.abs(Math.floor(px) - x) > slack || Math.abs(Math.floor(pz) - z) > slack) return false;
            return y == null || Math.abs(Math.floor(py) - y) <= slack;
        }
    }

    /**
     * Sink for navigation state. Exactly one <i>terminal</i> call — {@link #onArrived} or
     * {@link #onFailed} — fires per goal; the navigator guards against a Baritone event and the
     * fallback watchdog both reporting the same goal. {@link #onProgress} may fire any number of
     * times before that and never ends the goal.
     */
    interface Listener {
        void onArrived(Goal goal);

        /**
         * The goal is over and was not reached. {@code reason} is a terminal Baritone
         * {@code PathEvent} name ({@code CALC_FAILED}, {@code CANCELED}) or one of the navigator's
         * own: {@code CANCELLED}, {@code NO_PATH}, {@code REPLACED}.
         */
        void onFailed(Goal goal, String reason);

        /**
         * Baritone is still working on {@code goal} and something notable happened — a segment
         * recalculation, a splice, a discarded next segment. Purely informational: reporting these
         * as failures made callers abandon goals the bot went on to reach.
         */
        void onProgress(Goal goal, String event);
    }

    /** True if a real pathfinding backend (Baritone) was found at runtime. */
    boolean isAvailable();

    /** Human-readable backend name for status reporting. */
    String backend();

    /** Walk to an exact block. */
    void goTo(int x, int y, int z);

    /** Walk to within {@code reach} blocks of a block ({@code reach <= 1} means the block itself). */
    void goTo(int x, int y, int z, int reach);

    /** Walk to an X/Z column at any Y. */
    void goToXZ(int x, int z);

    /** Walk to within {@code reach} of an X/Z column at any Y. */
    void goToXZ(int x, int z, int reach);

    /**
     * Run a raw Baritone command (the full Baritone command set — {@code mine diamond_ore},
     * {@code follow player}, {@code goto x y z}, {@code build}, etc.) when Baritone is installed.
     */
    void runCommand(String command);

    /** Cancel all current pathing. */
    void stop();

    /** True if currently pathing somewhere. */
    boolean isActive();

    /** The goal set by the most recent {@code goTo*}, or null once it has completed or failed. */
    Goal goal();

    /** Installs the completion sink. Replaces any previous listener. */
    void setListener(Listener listener);

    /**
     * Per-client-tick progress watchdog. Fires {@link Listener#onArrived} when the player reaches
     * the goal and {@link Listener#onFailed} when pathing stops short of it. Cheap; safe to call
     * every tick even with no goal set.
     */
    void tick(double px, double py, double pz);
}
