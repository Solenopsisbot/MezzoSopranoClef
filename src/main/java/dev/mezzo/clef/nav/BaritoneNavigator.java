package dev.mezzo.clef.nav;

import dev.mezzo.clef.MezzoClef;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflective bridge to Baritone's public API ({@code baritone.api.*}). We avoid a compile-time
 * dependency on purpose: Baritone builds for a brand-new Minecraft version lag behind, and we
 * don't want the whole bot to fail to compile/run just because a 1.21.8 Baritone jar isn't
 * published yet. Drop a compatible {@code baritone-*.jar} into {@code run/mods/} (or add the
 * Gradle dependency) and this lights up automatically.
 *
 * <p>API surface used (stable across recent Baritone):
 * <pre>
 *   BaritoneAPI.getProvider().getPrimaryBaritone()
 *     .getCustomGoalProcess().setGoalAndPath(new GoalBlock(x,y,z) | new GoalXZ(x,z))
 *   baritone.getPathingBehavior().cancelEverything() / isPathing()
 * </pre>
 */
public final class BaritoneNavigator implements Navigator {

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
    private Object commandManager;           // ICommandManager
    private Method commandExecute;           // ICommandManager#execute(String)

    @Override
    public synchronized boolean isAvailable() {
        resolve();
        return available;
    }

    @Override
    public String backend() {
        return isAvailable() ? "baritone" : "none";
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

            // Full command set passthrough: IBaritone.getCommandManager().execute(String).
            try {
                commandManager = iBaritone.getMethod("getCommandManager").invoke(primaryBaritone);
                commandExecute = Class.forName("baritone.api.command.manager.ICommandManager")
                        .getMethod("execute", String.class);
            } catch (Throwable t) {
                MezzoClef.LOG.warn("Baritone present but command manager API not found: {}", t.toString());
            }

            available = primaryBaritone != null;
            MezzoClef.LOG.info("Baritone detected — pathfinding enabled.");
        } catch (ClassNotFoundException e) {
            MezzoClef.LOG.info("Baritone not on classpath — 'goto' commands disabled until you add it.");
            available = false;
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Baritone present but its API didn't match expectations: {}", t.toString());
            available = false;
        }
    }

    @Override
    public void goTo(int x, int y, int z) {
        requireAvailable();
        try {
            Object goal = goalBlockCtor.newInstance(x, y, z);
            Object process = getCustomGoalProcess.invoke(primaryBaritone);
            setGoalAndPath.invoke(process, goal);
        } catch (Exception e) {
            throw new RuntimeException("Baritone goto failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void goToXZ(int x, int z) {
        requireAvailable();
        try {
            Object goal = goalXZCtor.newInstance(x, z);
            Object process = getCustomGoalProcess.invoke(primaryBaritone);
            setGoalAndPath.invoke(process, goal);
        } catch (Exception e) {
            throw new RuntimeException("Baritone gotoXZ failed: " + e.getMessage(), e);
        }
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

    @Override
    public void stop() {
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

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Baritone is not available. Add a 1.21.8-compatible baritone jar to run/mods/ "
                            + "or as a Gradle dependency.");
        }
    }
}
