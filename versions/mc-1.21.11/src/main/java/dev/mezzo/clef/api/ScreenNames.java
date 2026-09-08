package dev.mezzo.clef.api;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;

/**
 * Stable, readable names for the screens a bot has to reason about.
 *
 * <p>{@code screen.getClass().getSimpleName()} is only useful in a development (Loom) run. The
 * shipped client is obfuscated, so the same screen reports {@code TitleScreen} in dev and
 * {@code class_424} in production — and a control client cannot tell "on the title screen" from
 * "connecting" from "dead". These names come from {@code instanceof} checks against classes we
 * hold a real reference to, so they survive remapping.</p>
 *
 * <p>The raw class name is still reported alongside, because for a modded screen it's the only
 * identifying information there is.</p>
 */
public final class ScreenNames {

    /** No screen at all — in-world with the HUD up. */
    public static final String NONE = "none";

    /**
     * One of: {@code none | title | connect | disconnected | death | pause | downloading |
     * message | inventory | crafting | furnace | anvil | enchanting | merchant | sign | book |
     * container | other}.
     *
     * <p>{@code container} is the catch-all for a server-synced menu (chest, shulker, hopper, a
     * modded machine) that isn't one of the specifically-named ones — anything drivable with
     * {@code container} / {@code clickSlot}. {@code other} is a pure-widget screen.</p>
     */
    public static String of(Screen screen) {
        if (screen == null) return NONE;
        if (screen instanceof TitleScreen) return "title";
        if (screen instanceof ConnectScreen) return "connect";
        if (screen instanceof DisconnectedScreen) return "disconnected";
        if (screen instanceof DeathScreen) return "death";
        // Worth its own name rather than falling into "other": the pause screen is the
        // one screen a headless bot can end up behind without ever asking for it, and it
        // blocks the whole input path while it is up.
        if (screen instanceof PauseScreen) return "pause";
        if (screen instanceof LevelLoadingScreen) return "downloading";
        if (screen instanceof GenericMessageScreen) return "message";
        if (screen instanceof InventoryScreen) return "inventory";
        if (screen instanceof CraftingScreen) return "crafting";
        if (screen instanceof FurnaceScreen) return "furnace";
        if (screen instanceof AnvilScreen) return "anvil";
        if (screen instanceof EnchantmentScreen) return "enchanting";
        if (screen instanceof MerchantScreen) return "merchant";
        if (screen instanceof SignEditScreen) return "sign";
        if (screen instanceof BookViewScreen) return "book";
        if (screen instanceof AbstractContainerScreen<?>) return "container";
        return "other";
    }

    /** The raw class name, obfuscated or not — the only handle a modded screen gives you. */
    public static String rawOf(Screen screen) {
        return screen == null ? NONE : screen.getClass().getSimpleName();
    }

    private ScreenNames() {}
}
