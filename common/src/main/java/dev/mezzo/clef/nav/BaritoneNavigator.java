package dev.mezzo.clef.nav;

import dev.mezzo.clef.MezzoClef;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Reflective bridge to Baritone's public API ({@code baritone.api.*}). We avoid a compile-time
 * dependency on purpose: Baritone builds for a brand-new Minecraft version lag behind, and we
 * don't want the whole bot to fail to compile/run just because a Baritone jar for the current Minecraft isn't
 * published yet. Drop a compatible {@code baritone-*.jar} into {@code run/mods/} (or add the
 * Gradle dependency) and this lights up automatically.
 *
 * <p>API surface used (stable across recent Baritone):
 * <pre>
 *   BaritoneAPI.getProvider().getPrimaryBaritone()
 *     .getCustomGoalProcess().setGoalAndPath(new GoalBlock(x,y,z) | new GoalXZ(x,z) | new GoalNear(pos,r))
 *   baritone.getPathingBehavior().cancelEverything() / isPathing()
 *   baritone.getGameEventHandler().registerEventListener(IGameEventListener)   // PathEvent stream
 *   BaritoneAPI.getSettings().logger.value = Consumer&lt;Text&gt;                    // Baritone's own output
 * </pre>
 *
 * <h2>Completion reporting</h2>
 * Scripts used to have to poll {@code nav.status} to find out whether a {@code goto} worked. Now a
 * goal finishes exactly once, through {@link Listener}, from whichever of two sources notices first:
 * <ol>
 *   <li><b>Baritone's {@code PathEvent} stream</b>, subscribed via a {@link Proxy} implementing
 *       {@code IGameEventListener}. This is the good source — it distinguishes {@code CALC_FAILED}
 *       from {@code CANCELED} from {@code AT_GOAL}.</li>
 *   <li><b>A watchdog</b> in {@link #tick}, for when the proxy couldn't be installed (older or
 *       repackaged Baritone) or when Baritone stops pathing without emitting a terminal event.</li>
 * </ol>
 * Clearing the goal under a lock makes the first source to notice the only one that reports it.
 */
public final class BaritoneNavigator implements Navigator {

    /** Ticks of "not pathing and not at the goal" the watchdog tolerates before calling it a failure.
     *  Generous because path <i>calculation</i> also counts as not-yet-pathing. */
    private static final int WATCHDOG_GRACE_TICKS = 100; // ~5s at 20 TPS

    private boolean resolved;
    private boolean available;

    private Object primaryBaritone;          // baritone.api.IBaritone
    private Method getCustomGoalProcess;     // IBaritone#getCustomGoalProcess()
    private Method setGoalAndPath;           // ICustomGoalProcess#setGoalAndPath(Goal)
    private Method getPathingBehavior;       // IBaritone#getPathingBehavior()
    private Method cancelEverything;         // IPathingBehavior#cancelEverything()
    private Method isPathing;                // IPathingBehavior#isPathing()
    private Constructor<?> goalBlockCtor;    // GoalBlock(int,int,int)
    private Constructor<?> goalXZCtor;       // GoalXZ(int,int)
    private Constructor<?> goalNearCtor;     // GoalNear(BlockPos,int)
    private Object commandManager;           // ICommandManager
    private Method commandExecute;           // ICommandManager#execute(String)
    private boolean pathEventsWired;
    /** Fan-out for Baritone's own output: the event stream plus any short-lived per-command tap. */
    private final java.util.List<Consumer<String>> logTaps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean logCaptured;

    /**
     * Completes once Baritone's block-drop machinery has been initialised off the client thread.
     * See {@link #warmUpBlockArguments()} — until this is done, any Baritone command naming a block
     * will hard-lock the client, so it is not optional.
     */
    private final CompletableFuture<Void> blockArgumentsReady = new CompletableFuture<>();
    private volatile boolean warmUpStarted;
    private volatile String warmUpError;

    private volatile Listener listener;
    private volatile Goal goal;
    private int watchdogIdleTicks;

    @Override
    public synchronized boolean isAvailable() {
        resolve();
        return available;
    }

    @Override
    public String backend() {
        return isAvailable() ? "baritone" : "none";
    }

    @Override
    public Goal goal() {
        return goal;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            primaryBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);

            Class<?> iBaritone = Class.forName("baritone.api.IBaritone");
            getCustomGoalProcess = iBaritone.getMethod("getCustomGoalProcess");
            getPathingBehavior = iBaritone.getMethod("getPathingBehavior");

            Class<?> goal = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> customGoalProcess = Class.forName("baritone.api.process.ICustomGoalProcess");
            setGoalAndPath = customGoalProcess.getMethod("setGoalAndPath", goal);

            Class<?> pathingBehavior = Class.forName("baritone.api.behavior.IPathingBehavior");
            cancelEverything = pathingBehavior.getMethod("cancelEverything");
            isPathing = pathingBehavior.getMethod("isPathing");

            goalBlockCtor = Class.forName("baritone.api.pathing.goals.GoalBlock")
                    .getConstructor(int.class, int.class, int.class);
            goalXZCtor = Class.forName("baritone.api.pathing.goals.GoalXZ")
                    .getConstructor(int.class, int.class);
            try {
                goalNearCtor = Class.forName("baritone.api.pathing.goals.GoalNear")
                        .getConstructor(BlockPos.class, int.class);
            } catch (Throwable t) {
                MezzoClef.LOG.warn("Baritone GoalNear not found — 'reach' will fall back to an exact goal: {}",
                        t.toString());
            }

            // Full command set passthrough: IBaritone.getCommandManager().execute(String).
            try {
                commandManager = iBaritone.getMethod("getCommandManager").invoke(primaryBaritone);
                commandExecute = Class.forName("baritone.api.command.manager.ICommandManager")
                        .getMethod("execute", String.class);
            } catch (Throwable t) {
                MezzoClef.LOG.warn("Baritone present but command manager API not found: {}", t.toString());
            }

            available = primaryBaritone != null;
            if (available) wirePathEvents(iBaritone);
            MezzoClef.LOG.info("Baritone detected — pathfinding enabled (path events: {}).",
                    pathEventsWired ? "live" : "watchdog only");
        } catch (ClassNotFoundException e) {
            MezzoClef.LOG.info("Baritone not on classpath — 'goto' commands disabled until you add it.");
            available = false;
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Baritone present but its API didn't match expectations: {}", t.toString());
            available = false;
        }
    }

    /**
     * Subscribes to Baritone's event bus with a dynamic proxy. Every {@code IGameEventListener}
     * method returns void, so the proxy can no-op everything except {@code onPathEvent} — which
     * means we don't have to track Baritone's event interface as it grows.
     */
    private void wirePathEvents(Class<?> iBaritone) {
        try {
            Class<?> listenerType = Class.forName("baritone.api.event.listener.IGameEventListener");
            Object bus = iBaritone.getMethod("getGameEventHandler").invoke(primaryBaritone);
            Object proxy = Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] { listenerType },
                    (self, method, args) -> {
                        if ("onPathEvent".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof Enum<?> event) {
                            onPathEvent(event.name());
                        }
                        return defaultValue(method.getReturnType());
                    });
            Class.forName("baritone.api.event.listener.IEventBus")
                    .getMethod("registerEventListener", listenerType)
                    .invoke(bus, proxy);
            pathEventsWired = true;
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Could not subscribe to Baritone path events, falling back to the "
                    + "tick watchdog: {}", t.toString());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return (char) 0;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == long.class) return 0L;
        return 0;   // byte/short/int all widen from this
    }

    /**
     * Baritone {@code PathEvent} names that actually end a goal. Everything else — including
     * {@code NEXT_CALC_FAILED}, which only means the <i>next</i> segment didn't calculate in time —
     * is Baritone still working, and reporting it as a failure was wrong: callers saw
     * {@code nav.failed} for a goal the bot then walked to two seconds later.
     */
    private static final Set<String> TERMINAL_PATH_EVENTS = Set.of("CALC_FAILED", "CANCELED", "CANCELLED");

    /** Called from Baritone's thread. Terminal events finish the goal; everything else is progress. */
    private void onPathEvent(String name) {
        if ("AT_GOAL".equals(name)) {
            complete(true, name);
            return;
        }
        if (TERMINAL_PATH_EVENTS.contains(name)) {
            complete(false, name);
            return;
        }
        // Still working. Keep the watchdog quiet and tell anyone listening what Baritone is doing;
        // a mid-path recalculation is exactly the sort of thing a supervising agent wants to see,
        // and it must not be confused with the goal ending.
        watchdogIdleTicks = 0;
        Listener sink = listener;
        Goal current = goal;
        if (sink != null && current != null) {
            try {
                sink.onProgress(current, name);
            } catch (Throwable t) {
                MezzoClef.LOG.warn("Navigation listener threw on progress: {}", t.toString());
            }
        }
    }

    // ---- goals ----------------------------------------------------------------------

    @Override
    public void goTo(int x, int y, int z) {
        goTo(x, y, z, 1);
    }

    @Override
    public void goTo(int x, int y, int z, int reach) {
        requireAvailable();
        try {
            Object target = reach > 1 && goalNearCtor != null
                    ? goalNearCtor.newInstance(new BlockPos(x, y, z), reach)
                    : goalBlockCtor.newInstance(x, y, z);
            startGoal(new Goal(x, y, z, reach), target);
        } catch (Exception e) {
            throw new RuntimeException("Baritone goto failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void goToXZ(int x, int z) {
        goToXZ(x, z, 1);
    }

    @Override
    public void goToXZ(int x, int z, int reach) {
        requireAvailable();
        try {
            startGoal(new Goal(x, null, z, reach), goalXZCtor.newInstance(x, z));
        } catch (Exception e) {
            throw new RuntimeException("Baritone gotoXZ failed: " + e.getMessage(), e);
        }
    }

    private void startGoal(Goal newGoal, Object baritoneGoal) throws Exception {
        // A goal that's being replaced never "finished" — say so rather than leaving it dangling.
        complete(false, "REPLACED");
        synchronized (this) {
            goal = newGoal;
            watchdogIdleTicks = 0;
        }
        Object process = getCustomGoalProcess.invoke(primaryBaritone);
        setGoalAndPath.invoke(process, baritoneGoal);
    }

    @Override
    public void runCommand(String command) {
        requireAvailable();
        if (commandManager == null || commandExecute == null) {
            throw new IllegalStateException("Baritone command manager unavailable");
        }
        try {
            commandExecute.invoke(commandManager, command);
        } catch (Exception e) {
            throw new RuntimeException("Baritone command '" + command + "' failed: " + e.getMessage(), e);
        }
    }

    // ---- the block-argument deadlock -------------------------------------------------

    /**
     * Kicks off the one piece of Baritone initialisation that <b>must not</b> happen on the client
     * thread. Safe to call repeatedly; only the first call does anything.
     *
     * <h2>What goes wrong without this</h2>
     * Any Baritone command naming a block — {@code goto jungle_log}, {@code mine diamond_ore},
     * {@code sel fill stone} — parses that argument into a {@code BlockOptionalMeta}, whose
     * constructor computes the block's drops by spinning up a stub server level and reading loot
     * tables. That stub gets its registries from a static {@code CompletableFuture} whose work is
     * scheduled <i>onto the client's main-thread executor</i> and then {@code join()}ed.
     *
     * <p>Join it from the client thread and the client thread is now waiting for a task only the
     * client thread can run. It never returns. Every subsequent command that hops onto the main
     * thread times out, and the only way out is killing the JVM. Coordinates are unaffected, which
     * is why {@code goto 106 89 120} looks fine and {@code goto jungle_log} bricks the bot.
     *
     * <p>The future is static and computed once, so touching it from <i>any other thread</i> — with
     * the client thread free to drain its queue — resolves it permanently. After that every later
     * call, including on the client thread, joins an already-completed future and returns straight
     * away. So: build one throwaway {@code BlockOptionalMeta} on a worker at startup, and the whole
     * class of commands becomes safe forever.
     */
    public void warmUpBlockArguments() {
        synchronized (this) {
            if (warmUpStarted || !isAvailable()) return;
            warmUpStarted = true;
        }
        Thread worker = new Thread(() -> {
            long start = System.nanoTime();
            try {
                Class<?> bom = Class.forName("baritone.api.utils.BlockOptionalMeta");
                // Stone is arbitrary — the expensive, blocking part is the shared registry future,
                // not this particular block's loot table.
                bom.getConstructor(Block.class).newInstance(Blocks.STONE);
                MezzoClef.LOG.info("Baritone block arguments ready ({} ms) — 'goto <block>' and "
                        + "'mine <block>' are safe.", (System.nanoTime() - start) / 1_000_000);
                blockArgumentsReady.complete(null);
            } catch (Throwable t) {
                warmUpError = t.toString();
                MezzoClef.LOG.warn("Could not pre-initialise Baritone block arguments; commands "
                        + "naming a block will be refused rather than risk deadlocking the client: {}",
                        warmUpError);
                blockArgumentsReady.complete(null);   // unblock waiters; blockArgumentsSafe() stays false
            }
        }, "clef-baritone-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /** True once a Baritone command naming a block can be run without parking the client thread. */
    public boolean blockArgumentsSafe() {
        return blockArgumentsReady.isDone() && warmUpError == null;
    }

    /** {@code ready}, {@code pending}, {@code failed: ...}, or {@code unavailable}. */
    public String blockArgumentsState() {
        if (!isAvailable()) return "unavailable";
        if (warmUpError != null) return "failed: " + warmUpError;
        return blockArgumentsReady.isDone() ? "ready" : "pending";
    }

    /**
     * Blocks <b>the calling thread</b> until block arguments are safe.
     *
     * <p>Must not be called from the client thread: the warm-up needs the client thread to drain its
     * task queue, so waiting on it there recreates the very deadlock this exists to prevent. The
     * command layer calls this before hopping onto the main thread.
     *
     * @return true if it is now safe to run a Baritone command naming a block
     */
    public boolean awaitBlockArguments(long timeoutMillis) {
        if (!isAvailable()) return false;
        warmUpBlockArguments();
        try {
            blockArgumentsReady.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
        return warmUpError == null;
    }

    // ---- Baritone's own output -------------------------------------------------------

    /**
     * Adds a sink for everything Baritone prints. Baritone reports {@code find} results,
     * {@code eta}, "No known locations of ...", build progress and missing materials through its
     * logger straight into the local chat HUD — which a headless bot does not have, making every
     * Baritone process a black box that either works or silently doesn't.
     *
     * <p>Taps are additive so the pushed {@code baritone.log} event and a per-command output
     * collector can coexist. The reflective install happens once, on the first tap.</p>
     */
    public void addLogTap(Consumer<String> tap) {
        if (tap == null) return;
        logTaps.add(tap);
        installLogCapture();
    }

    public void removeLogTap(Consumer<String> tap) {
        logTaps.remove(tap);
    }

    /** Replaces Baritone's logger with one that fans out to {@link #logTaps}. Idempotent. */
    private synchronized void installLogCapture() {
        if (logCaptured || !isAvailable()) return;
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object settings = api.getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField("logger").get(settings);
            Field value = setting.getClass().getField("value");
            // Settings.Setting<Consumer<Text>>; erasure means a plain Consumer assigns cleanly.
            Consumer<Object> forward = message -> {
                if (!(message instanceof Component text)) return;
                String line = text.getString();
                for (Consumer<String> tap : logTaps) {
                    try {
                        tap.accept(line);
                    } catch (Throwable ignored) {
                        // one bad sink must not silence Baritone for everyone else
                    }
                }
            };
            value.set(setting, forward);
            logCaptured = true;
            MezzoClef.LOG.info("Baritone log output is being forwarded to the control plane.");
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Could not capture Baritone log output: {}", t.toString());
        }
    }

    @Override
    public void stop() {
        complete(false, "CANCELLED");
        if (!isAvailable()) return;
        try {
            Object behavior = getPathingBehavior.invoke(primaryBaritone);
            cancelEverything.invoke(behavior);
        } catch (Exception e) {
            throw new RuntimeException("Baritone stop failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isActive() {
        if (!isAvailable()) return false;
        try {
            Object behavior = getPathingBehavior.invoke(primaryBaritone);
            return (boolean) isPathing.invoke(behavior);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- completion -----------------------------------------------------------------

    @Override
    public void tick(double px, double py, double pz) {
        Goal current = goal;
        if (current == null) return;
        if (current.satisfiedBy(px, py, pz)) {
            complete(true, "AT_GOAL");
            return;
        }
        if (isActive()) {
            watchdogIdleTicks = 0;
            return;
        }
        if (++watchdogIdleTicks > WATCHDOG_GRACE_TICKS) {
            complete(false, "NO_PATH");
        }
    }

    /**
     * Reports the current goal as finished, at most once. Both the Baritone event stream and the
     * watchdog call this; whichever arrives first clears {@code goal} under the lock, so the other
     * becomes a no-op.
     */
    private void complete(boolean arrived, String reason) {
        Goal finished;
        synchronized (this) {
            finished = goal;
            if (finished == null) return;
            goal = null;
            watchdogIdleTicks = 0;
        }
        Listener sink = listener;
        if (sink == null) return;
        try {
            if (arrived) sink.onArrived(finished);
            else sink.onFailed(finished, reason);
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Navigation listener threw: {}", t.toString());
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Baritone is not available. Add a baritone jar for this Minecraft version to run/mods/ "
                            + "or as a Gradle dependency.");
        }
    }
}
