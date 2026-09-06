package dev.mezzo.clef.bot;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * Crafting is unavailable on this Minecraft release.
 *
 * <p>The recipe-display API the real {@code CraftManager} drives — {@code RecipeDisplay},
 * {@code SlotDisplay}, {@code AbstractCraftingMenu} — only exists from 1.21.4 onward. Rather than
 * change the shared {@code ClefServices} shape per release, this keeps the same type present so
 * {@code common/} compiles everywhere, and reports the feature as unsupported.
 */
public final class CraftManager {

    /** Same shape as the real implementation's result. */
    public record Result(int crafted, String reason) {}

    /** Never busy: nothing here ever starts. */
    public boolean isBusy() {
        return false;
    }

    /** Nothing to cancel. */
    public void cancel(String reason) {
    }

    /** Nothing to drive per tick. */
    public void tick(Minecraft mc) {
    }

    /** Always fails with a clear reason, so callers get an honest answer rather than a crash. */
    public CompletableFuture<Result> start(Minecraft mc, Object menu, Object recipe, int count, boolean all) {
        return CompletableFuture.completedFuture(
                new Result(0, "crafting needs Minecraft 1.21.4 or newer (no recipe-display API here)"));
    }
}
