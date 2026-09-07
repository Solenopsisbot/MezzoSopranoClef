package dev.mezzo.clef.bot;

import dev.mezzo.clef.MezzoClef;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * Drives crafting the way a player does, because that is the only way the server accepts it.
 *
 * <p>There is no "craft me a stick" packet. Vanilla's flow is: ask the server to lay a known recipe
 * out in the crafting grid ({@code clickRecipe} → {@code CraftRequestC2SPacket}), wait for it to
 * sync the grid and the result slot back, then shift-click the result to actually consume the
 * inputs. Every one of those steps is a round-trip, so crafting cannot be a synchronous function —
 * it's a small state machine ticked on the client thread, which is what this is.</p>
 *
 * <h2>How many did we make?</h2>
 * The client can't predict the result (the server owns recipe matching), so we don't try. We count
 * the target item in the inventory before and after, and report the difference. That is robust to
 * partial crafts, to a server that refuses halfway, and to recipes that yield 4 planks per log.
 * The trade-off: if something <i>else</i> gives you the same item mid-craft, it gets counted. In a
 * bot that just asked to craft, that's a rounding error worth the simplicity.
 */
public final class CraftManager {

    /** Ticks to wait for the server to fill the grid before deciding the recipe won't come. */
    private static final int FILL_TIMEOUT_TICKS = 60;
    /** Ticks to let inventory sync settle after taking the result, before recounting. */
    private static final int SETTLE_TICKS = 3;
    /** Whole-request cap, so a server that silently drops craft packets can't hang the command. */
    private static final int TOTAL_TIMEOUT_TICKS = 600; // ~30s

    /**
     * @param crafted how many of the requested item appeared in the inventory
     * @param reason  {@code null} on a full success (which for {@code all} means "made everything
     *                the inventory allowed"), else {@code partial} / {@code no_result} /
     *                {@code timeout} / {@code screen_changed}
     */
    public record Result(int crafted, String reason) {}

    private enum Phase { IDLE, REQUEST, WAIT_RESULT, SETTLE, CLEANUP }

    private Phase phase = Phase.IDLE;
    private RecipeDisplayId recipeId;
    private Item target;
    private int wanted;
    private boolean all;
    private int syncId;
    private int baseline;
    private int lastCount;
    private int phaseTicks;
    private int totalTicks;
    private CompletableFuture<Result> future;
    /** Outcome decided when we entered CLEANUP; reported once the grid has been emptied. */
    private String pendingReason;
    private int pendingCrafted;
    private boolean cleanupClicked;

    public boolean isBusy() {
        return phase != Phase.IDLE;
    }

    /**
     * Starts a craft. Must be called on the client thread with a crafting-capable screen handler
     * open (the 2×2 player grid counts).
     *
     * @param all craft as many as the inventory allows, ignoring {@code count}
     */
    public CompletableFuture<Result> start(Minecraft mc, AbstractCraftingMenu handler,
                                           RecipeDisplayId recipeId, Item target, int count, boolean all) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        if (isBusy()) {
            result.completeExceptionally(new IllegalStateException("a craft is already in progress"));
            return result;
        }
        this.recipeId = recipeId;
        this.target = target;
        this.wanted = all ? Integer.MAX_VALUE : Math.max(1, count);
        this.all = all;
        this.syncId = handler.containerId;
        this.baseline = countTarget(mc);
        this.lastCount = baseline;
        this.phase = Phase.REQUEST;
        this.phaseTicks = 0;
        this.totalTicks = 0;
        this.cleanupClicked = false;
        this.future = result;
        return result;
    }

    /** Aborts any in-flight craft (used when the bot leaves the world). */
    public void cancel(String reason) {
        if (phase == Phase.IDLE) return;
        finish(lastCount - baseline, reason);
    }

    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.gameMode == null) {
            finish(lastCount - baseline, "screen_changed");
            return;
        }
        if (++totalTicks > TOTAL_TIMEOUT_TICKS) {
            finish(countTarget(mc) - baseline, "timeout");
            return;
        }
        if (!(mc.player.containerMenu instanceof AbstractCraftingMenu handler)
                || handler.containerId != syncId) {
            // Someone opened a chest (or the server swapped our handler) — the recipe we're mid-way
            // through no longer has a grid to land in. Report what we got rather than clicking blind.
            finish(countTarget(mc) - baseline, "screen_changed");
            return;
        }
        phaseTicks++;

        switch (phase) {
            case REQUEST -> {
                // craftAll fills the grid with as many recipe repetitions as the inventory allows,
                // so a single shift-click on the output can produce a whole stack in one round-trip.
                mc.gameMode.handlePlaceRecipe(syncId, recipeId, all || wanted > 1);
                phase = Phase.WAIT_RESULT;
                phaseTicks = 0;
            }
            case WAIT_RESULT -> {
                Slot output = handler.getResultSlot();
                if (!output.getItem().isEmpty()) {
                    mc.gameMode.handleContainerInput(syncId, output.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                    phase = Phase.SETTLE;
                    phaseTicks = 0;
                } else if (phaseTicks > FILL_TIMEOUT_TICKS) {
                    int crafted = countTarget(mc) - baseline;
                    phase = Phase.CLEANUP;
                    phaseTicks = 0;
                    pendingReason = crafted > 0 ? "partial" : "no_result";
                    pendingCrafted = crafted;
                }
            }
            case SETTLE -> {
                if (phaseTicks < SETTLE_TICKS) return;
                int count = countTarget(mc);
                int crafted = count - baseline;
                if (crafted >= wanted) {
                    phase = Phase.CLEANUP;
                    phaseTicks = 0;
                    pendingReason = null;
                    pendingCrafted = crafted;
                } else if (count == lastCount) {
                    // An entire cycle produced nothing: out of materials, or the server refused.
                    // For `all` that IS the success condition — we asked for everything possible and
                    // have now made it — so only a bounded request that fell short is "partial".
                    phase = Phase.CLEANUP;
                    phaseTicks = 0;
                    pendingReason = crafted > 0 ? (all ? null : "partial") : "no_result";
                    pendingCrafted = crafted;
                } else {
                    lastCount = count;
                    phase = Phase.REQUEST;
                    phaseTicks = 0;
                }
            }
            case CLEANUP -> {
                // Shift anything the server left sitting in the grid back into the inventory. Without
                // this, leftovers are scattered on the ground the moment the screen closes. Click once,
                // then give the sync a beat before reporting, so the caller's next `inventory` is right.
                if (!cleanupClicked) {
                    cleanupClicked = true;
                    for (Slot input : handler.getInputGridSlots()) {
                        if (input.getItem().isEmpty()) continue;
                        mc.gameMode.handleContainerInput(syncId, input.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                    }
                    phaseTicks = 0;
                    return;
                }
                if (phaseTicks < SETTLE_TICKS) return;
                finish(pendingCrafted, pendingReason);
            }
            case IDLE -> { }
        }
    }

    private int countTarget(Minecraft mc) {
        if (mc.player == null) return lastCount;
        var inv = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.getItem() == target) total += stack.getCount();
        }
        return total;
    }

    private void finish(int crafted, String reason) {
        CompletableFuture<Result> sink = future;
        phase = Phase.IDLE;
        future = null;
        recipeId = null;
        target = null;
        pendingReason = null;
        pendingCrafted = 0;
        cleanupClicked = false;
        if (sink == null) return;
        try {
            sink.complete(new Result(Math.max(0, crafted), reason));
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Craft completion threw: {}", t.toString());
        }
    }
}
