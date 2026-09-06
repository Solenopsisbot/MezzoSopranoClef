package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.api.ErrorCode;
import dev.mezzo.clef.bot.CraftManager;
import dev.mezzo.clef.bot.RecipeIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.util.context.ContextParameterMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Crafting. Previously the only way to make anything was to drive the grid by hand with
 * {@code clickSlot}, which meant reimplementing recipe layout in the caller and getting it wrong
 * whenever a recipe was shaped.
 *
 * <p>All three commands read the <b>client recipe book</b> — the recipes this server has unlocked
 * for this account. That's a feature, not a limitation: a recipe missing from it is one the server
 * would refuse anyway, so {@code craftable} tells you what will actually work here rather than what
 * works in vanilla.</p>
 *
 * @see RecipeIndex for how a 1.21.2+ client answers recipe questions without holding real recipes
 * @see CraftManager for why crafting is a state machine rather than a function call
 */
public final class CraftCommands {

    /** Generous: a full stack of a multi-step recipe on a laggy server still finishes inside this. */
    private static final int CRAFT_TIMEOUT_SECONDS = 45;

    public static void registerAll(CommandDispatcher d) {

        d.register("craft",
                "craft an item using the open crafting screen, else the 2x2 player grid {item, count?=1, all?}",
                ctx -> {
                    String itemId = ctx.requireStr("item");
                    int count = Math.max(1, ctx.i("count", 1));
                    boolean all = ctx.bool("all", false);

                    CompletableFuture<CraftManager.Result> pending = ctx.onMain(() -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player == null || mc.world == null) throw ApiException.notInWorld();
                        Item item = RecipeIndex.resolveItem(itemId);
                        AbstractCraftingScreenHandler handler = craftingHandler(mc);
                        mc.player.getRecipeBook().refresh();
                        ContextParameterMap params = RecipeIndex.context(mc.world);
                        List<RecipeIndex.Match> candidates =
                                RecipeIndex.producing(mc.player.getRecipeBook(), params, item);
                        if (candidates.isEmpty()) {
                            throw ApiException.notFound("no known recipe produces '" + itemId
                                    + "' (the server may not have unlocked it)");
                        }
                        RecipeIndex.Match chosen = choose(handler, candidates, mc);
                        return ctx.server.services.craft.start(mc, handler, chosen.entry().id(),
                                item, count, all);
                    });

                    CraftManager.Result result = pending.get(CRAFT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    JsonObject o = new JsonObject();
                    o.addProperty("crafted", result.crafted());
                    o.addProperty("item", Registries.ITEM.getId(RecipeIndex.resolveItem(itemId)).toString());
                    if (result.reason() != null) o.addProperty("reason", result.reason());
                    return o;
                });

        d.register("recipes", "known recipes that produce an item {item}", ctx -> {
            String itemId = ctx.requireStr("item");
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.world == null) throw ApiException.notInWorld();
                Item item = RecipeIndex.resolveItem(itemId);
                mc.player.getRecipeBook().refresh();   // see `craftable` — the ordered view goes stale
                ContextParameterMap params = RecipeIndex.context(mc.world);
                JsonArray arr = new JsonArray();
                for (RecipeIndex.Match match : RecipeIndex.producing(mc.player.getRecipeBook(), params, item)) {
                    arr.add(RecipeIndex.describe(match, params));
                }
                return arr;
            });
        });

        d.register("craftable", "everything the current inventory can make right now", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null) throw ApiException.notInWorld();
            ClientRecipeBook book = mc.player.getRecipeBook();
            // The ordered view is rebuilt by refresh(), which vanilla only calls when the recipe
            // book screen reacts to an unlock. A headless bot never opens that screen, so refresh
            // here or the answer can be one packet stale.
            book.refresh();
            ContextParameterMap params = RecipeIndex.context(mc.world);

            // Populate the finder the way vanilla does, from the inventory *and* anything already
            // sitting in the open crafting grid.
            RecipeFinder finder = new RecipeFinder();
            var inv = mc.player.getInventory();
            inv.populateRecipeFinder(finder);

            AbstractCraftingScreenHandler handler =
                    mc.player.currentScreenHandler instanceof AbstractCraftingScreenHandler h ? h : null;
            if (handler != null) handler.populateRecipeFinder(finder);
            // Whether a recipe is *reachable* depends on the grid in front of us, so say which one
            // we measured against — 2x2 answers differ from 3x3 and silently confuse callers.
            int width = handler != null ? handler.getWidth() : 2;
            int height = handler != null ? handler.getHeight() : 2;

            Map<Item, Integer> counts = new HashMap<>();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }

            JsonArray arr = new JsonArray();
            for (RecipeIndex.Match match : RecipeIndex.craftableNow(book, params, finder, counts)) {
                JsonObject o = new JsonObject();
                o.addProperty("item", Registries.ITEM.getId(match.result().getItem()).toString());
                o.addProperty("count", match.result().getCount());
                o.addProperty("fitsOpenGrid", RecipeIndex.fitsGrid(match.entry().display(), width, height));
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.addProperty("grid", width + "x" + height);
            // An empty list has two very different causes — nothing affordable, or the server hasn't
            // unlocked any recipes for this account yet. Say which, so it isn't a silent mystery.
            out.addProperty("knownRecipes", RecipeIndex.all(book).size());
            out.add("items", arr);
            return out;
        }));
    }

    /**
     * The crafting grid to use: whatever screen is open if it has one, otherwise the player's own
     * 2×2. With no screen open, {@code currentScreenHandler} <i>is</i> the player screen handler, so
     * the common case needs no special handling — but a chest or a furnace is not somewhere a craft
     * request can land, and saying so beats sending a packet the server will drop.
     */
    private static AbstractCraftingScreenHandler craftingHandler(MinecraftClient mc) {
        if (mc.player.currentScreenHandler instanceof AbstractCraftingScreenHandler handler) return handler;
        throw new ApiException(ErrorCode.BAD_ARGS,
                "the open screen (" + mc.player.currentScreenHandler.getClass().getSimpleName()
                        + ") has no crafting grid — close it, or open a crafting table");
    }

    /**
     * Picks which of several recipes for the same item to send. Preference order: fits the open grid
     * and the inventory can afford it; then merely fits. If nothing fits, the error names the reason
     * a caller can act on — almost always "this needs a 3×3 table and you're on the 2×2 grid".
     */
    private static RecipeIndex.Match choose(AbstractCraftingScreenHandler handler,
                                            List<RecipeIndex.Match> candidates, MinecraftClient mc) {
        int width = handler.getWidth();
        int height = handler.getHeight();
        RecipeFinder finder = new RecipeFinder();
        handler.populateRecipeFinder(finder);

        RecipeIndex.Match fitsButUnaffordable = null;
        for (RecipeIndex.Match match : candidates) {
            if (!RecipeIndex.fitsGrid(match.entry().display(), width, height)) continue;
            if (match.entry().isCraftable(finder)) return match;
            if (fitsButUnaffordable == null) fitsButUnaffordable = match;
        }
        if (fitsButUnaffordable != null) return fitsButUnaffordable;   // let the server have the last word
        throw ApiException.badArgs("no recipe for this item fits the open " + width + "x" + height
                + " grid — open a crafting table for 3x3 recipes");
    }

    private CraftCommands() {}
}
