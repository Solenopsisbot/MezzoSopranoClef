package dev.mezzo.clef.bot;

import dev.mezzo.clef.api.ApiException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Getting a specific item into the bot's hand — the small, fiddly step that sits in front of half
 * the useful actions ("place a torch", "eat bread", "hit it with the axe").
 *
 * <p>Minecraft has no "hold this item" operation. You can only select one of nine hotbar slots, and
 * move stacks between slots by clicking. So this does what a player does: use it if it's already on
 * the hotbar, otherwise swap it there from the main inventory (vanilla's number-key swap, which is
 * a single {@link ContainerInput#SWAP} click) and then select it.</p>
 *
 * <p>Must run on the client thread.</p>
 */
public final class Hotbar {

    /** Hotbar slots are inventory indices 0-8; everything above is main inventory / equipment. */
    public static final int HOTBAR_SIZE = 9;

    /**
     * @param slot     the hotbar slot the item ended up in, now selected
     * @param moved    true if the item had to be swapped in from the main inventory
     * @param fromSlot the inventory index it came from ({@code -1} if it was already on the hotbar)
     */
    public record Selection(int slot, boolean moved, int fromSlot) {}

    /**
     * Selects {@code item}, moving it onto the hotbar if needed.
     *
     * @param preferredSlot hotbar slot to swap into, or null to pick an empty one (falling back to
     *                      the currently selected slot, whose contents get swapped out — not lost)
     * @throws ApiException {@code NOT_FOUND} if the inventory has none of the item
     */
    public static Selection select(Minecraft mc, Item item, Integer preferredSlot) {
        if (mc.player == null || mc.gameMode == null) throw ApiException.notInWorld();
        if (preferredSlot != null && (preferredSlot < 0 || preferredSlot >= HOTBAR_SIZE)) {
            throw ApiException.badArgs("slot must be 0-8");
        }
        Inventory inv = mc.player.getInventory();

        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inv.getItem(i).getItem() == item) {
                inv.setSelectedSlot(i);
                return new Selection(i, false, i);
            }
        }

        int source = -1;
        for (int i = HOTBAR_SIZE; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) {
                source = i;
                break;
            }
        }
        if (source < 0) {
            throw ApiException.notFound("no '"
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item) + "' in inventory");
        }

        int target = preferredSlot != null ? preferredSlot : firstEmptyHotbarSlot(inv);
        AbstractContainerMenu handler = mc.player.containerMenu;
        Slot sourceSlot = findSlot(handler, inv, source);
        if (sourceSlot == null) {
            // Only possible with an exotic screen handler that doesn't expose the main inventory.
            throw ApiException.notFound("inventory slot " + source + " is not reachable from the open screen");
        }
        // SWAP's "button" is the destination hotbar index — this is exactly the vanilla number-key
        // swap, so whatever was in the target slot goes back where the item came from.
        mc.gameMode.handleContainerInput(handler.containerId, sourceSlot.index, target, ContainerInput.SWAP, mc.player);
        inv.setSelectedSlot(target);
        return new Selection(target, true, source);
    }

    /** An empty hotbar slot if there is one, else the slot currently selected. */
    private static int firstEmptyHotbarSlot(Inventory inv) {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return inv.getSelectedSlot();
    }

    /**
     * Maps a {@link Inventory} index to the {@link Slot} that represents it in the open screen
     * handler. Slot ids shift depending on what's open (a chest pushes the player inventory down by
     * the container's size), so this can't be a constant offset.
     */
    private static Slot findSlot(AbstractContainerMenu handler, Inventory inv, int invIndex) {
        for (Slot slot : handler.slots) {
            if (slot.container == inv && slot.getContainerSlot() == invIndex) return slot;
        }
        return null;
    }

    /** Total count of {@code item} across the whole player inventory. */
    public static int count(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private Hotbar() {}
}
