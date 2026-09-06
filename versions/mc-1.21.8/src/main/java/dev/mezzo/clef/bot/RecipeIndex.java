package dev.mezzo.clef.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

/**
 * Reads the client's recipe book — the set of recipes <b>this server</b> told us about — and answers
 * the three questions a crafting bot has: what makes X, what can I make right now, and which recipe
 * should I actually send.
 *
 * <p>Since 1.21.2 the client no longer holds real {@code Recipe} objects. It holds
 * {@link RecipeDisplay}s: a description of what the recipe book should draw, keyed by a
 * {@link net.minecraft.world.item.crafting.display.RecipeDisplayId} that the server will accept back in a craft request.
 * That's less information than the server has, but it is exactly enough — the ingredients, the
 * result, the grid size, and the id to send.</p>
 *
 * <p>Everything here must run on the client thread; {@link SlotDisplayContext#fromLevel}
 * needs the live world's registries to resolve item stacks.</p>
 */
public final class RecipeIndex {

    /** A recipe plus its resolved result, so callers don't re-resolve stacks they already looked at. */
    public record Match(RecipeDisplayEntry entry, ItemStack result) {}

    /** Builds the parameter map {@link SlotDisplay#resolveForStacks} needs to turn displays into stacks. */
    public static ContextMap context(Level world) {
        return SlotDisplayContext.fromLevel(world);
    }

    /** Every recipe the server has unlocked for us, flattened out of the recipe book's grouping. */
    public static List<RecipeDisplayEntry> all(ClientRecipeBook book) {
        List<RecipeDisplayEntry> out = new ArrayList<>();
        for (RecipeCollection collection : book.getCollections()) {
            out.addAll(collection.getRecipes());
        }
        return out;
    }

    /** Recipes producing {@code item}, in recipe-book order (vanilla's own preference order). */
    public static List<Match> producing(ClientRecipeBook book, ContextMap ctx, Item item) {
        List<Match> out = new ArrayList<>();
        for (RecipeDisplayEntry entry : all(book)) {
            for (ItemStack stack : entry.display().result().resolveForStacks(ctx)) {
                if (stack.getItem() == item) {
                    out.add(new Match(entry, stack));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Everything the current inventory can make right now.
     *
     * <p>Craftability is decided two ways, and the union is what's returned:</p>
     * <ol>
     *   <li><b>Vanilla's own answer.</b> {@link RecipeCollection#selectRecipes} then
     *       {@link RecipeCollection.CraftableStatus#CRAFTABLE} — exactly what the recipe book
     *       screen would show.</li>
     *   <li><b>Our own check, for entries vanilla refuses to judge.</b>
     *       {@link RecipeDisplayEntry#canCraft} returns a flat {@code false} whenever the server
     *       didn't send {@code craftingRequirements} for that recipe — indistinguishable from "you
     *       can't make this". That produced an empty {@code craftable} list for a bot holding a
     *       stack of planks. So for those entries we match the display's own ingredient slots
     *       against the inventory ourselves.</li>
     * </ol>
     *
     * @param counts item id → how many the bot holds, for the fallback check
     */
    public static List<Match> craftableNow(ClientRecipeBook book, ContextMap ctx,
                                           StackedItemContents finder, Map<Item, Integer> counts) {
        Set<RecipeDisplayId> craftable = new java.util.HashSet<>();
        for (RecipeCollection collection : book.getCollections()) {
            collection.selectRecipes(finder, display -> true);
            for (RecipeDisplayEntry entry : collection.getSelectedRecipes(
                    RecipeCollection.CraftableStatus.CRAFTABLE)) {
                craftable.add(entry.id());
            }
        }

        List<Match> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RecipeDisplayEntry entry : all(book)) {
            boolean ok = craftable.contains(entry.id())
                    || (entry.craftingRequirements().isEmpty() && affordable(entry, ctx, counts));
            if (!ok) continue;
            for (ItemStack stack : entry.display().result().resolveForStacks(ctx)) {
                if (stack.isEmpty()) continue;
                if (seen.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount())) {
                    out.add(new Match(entry, stack));
                }
            }
        }
        return out;
    }

    /**
     * Greedy multiset match of a recipe's displayed ingredient slots against what the bot holds.
     * Each slot needs one item; a slot that accepts a tag is satisfied by any member still in stock.
     *
     * <p>Greedy can in principle mis-order two tag slots with overlapping options, so this is a
     * best-effort answer for recipes vanilla gave us no requirements for — never the primary path.</p>
     */
    private static boolean affordable(RecipeDisplayEntry entry, ContextMap ctx,
                                      Map<Item, Integer> counts) {
        List<SlotDisplay> ingredients = ingredientsOf(entry.display());
        if (ingredients.isEmpty()) return false;
        Map<Item, Integer> remaining = new java.util.HashMap<>(counts);
        for (SlotDisplay slot : ingredients) {
            if (slot instanceof SlotDisplay.Empty) continue;
            Item chosen = null;
            for (ItemStack option : slot.resolveForStacks(ctx)) {
                Item item = option.getItem();
                if (remaining.getOrDefault(item, 0) > 0) {
                    chosen = item;
                    break;
                }
            }
            if (chosen == null) return false;
            remaining.merge(chosen, -1, Integer::sum);
        }
        return true;
    }

    /** Serializes a recipe the way the {@code recipes} command reports it. */
    public static JsonObject describe(Match match, ContextMap ctx) {
        RecipeDisplay display = match.entry().display();
        JsonObject out = new JsonObject();
        out.addProperty("result", BuiltInRegistries.ITEM.getKey(match.result().getItem()).toString());
        out.addProperty("count", match.result().getCount());
        out.addProperty("recipeId", match.entry().id().index());
        out.addProperty("kind", kindOf(display));

        List<SlotDisplay> ingredients = ingredientsOf(display);
        JsonArray arr = new JsonArray();
        for (SlotDisplay slot : ingredients) {
            JsonObject ing = describeSlot(slot, ctx);
            if (ing != null) arr.add(ing);
        }
        out.add("ingredients", arr);

        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            out.addProperty("width", shaped.width());
            out.addProperty("height", shaped.height());
            // A 2x2 player grid can't hold a 3-wide or 3-tall recipe.
            out.addProperty("needsTable", shaped.width() > 2 || shaped.height() > 2);
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            out.addProperty("needsTable", shapeless.ingredients().size() > 4);
        } else {
            out.addProperty("needsTable", false);   // furnace/stonecutter/smithing: not a table at all
        }
        ItemStack station = display.craftingStation().resolveForFirstStack(ctx);
        if (!station.isEmpty()) out.addProperty("station", BuiltInRegistries.ITEM.getKey(station.getItem()).toString());
        return out;
    }

    /** True if this recipe can be laid out in a {@code width × height} crafting grid. */
    public static boolean fitsGrid(RecipeDisplay display, int width, int height) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() <= width && shaped.height() <= height;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size() <= width * height;
        }
        return false;   // furnace/stonecutter/smithing recipes are not craftable in a grid at all
    }

    /** Resolves an item id, accepting a bare path as {@code minecraft:<path>}. */
    public static Item resolveItem(String id) {
        String trimmed = id == null ? "" : id.trim();
        ResourceLocation parsed = trimmed.indexOf(':') >= 0
                ? ResourceLocation.tryParse(trimmed) : ResourceLocation.tryBuild("minecraft", trimmed);
        if (parsed == null) throw ApiException.badArgs("not a valid item id: '" + id + "'");
        return BuiltInRegistries.ITEM.getOptional(parsed)
                .orElseThrow(() -> ApiException.badArgs("unknown item: '" + id + "'"));
    }

    // ---- internals ------------------------------------------------------------------

    private static List<SlotDisplay> ingredientsOf(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients();
        if (display instanceof net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay furnace) {
            return List.of(furnace.ingredient());
        }
        if (display instanceof net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay cutter) {
            return List.of(cutter.input());
        }
        if (display instanceof net.minecraft.world.item.crafting.display.SmithingRecipeDisplay smithing) {
            return List.of(smithing.template(), smithing.base(), smithing.addition());
        }
        return List.of();
    }

    private static String kindOf(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay) return "crafting_shaped";
        if (display instanceof ShapelessCraftingRecipeDisplay) return "crafting_shapeless";
        if (display instanceof net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay) return "smelting";
        if (display instanceof net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay) return "stonecutting";
        if (display instanceof net.minecraft.world.item.crafting.display.SmithingRecipeDisplay) return "smithing";
        return "other";
    }

    /**
     * One ingredient slot. A tag ingredient reports its {@code tag} <i>and</i> the items it expands
     * to, capped, because "any log" is more useful to an agent than eleven near-identical ids —
     * but the expansion is what it has to actually go and find.
     */
    private static JsonObject describeSlot(SlotDisplay slot, ContextMap ctx) {
        if (slot instanceof SlotDisplay.Empty) return null;
        JsonObject out = new JsonObject();
        out.addProperty("count", 1);   // grid slots always take exactly one item
        if (slot instanceof SlotDisplay.TagSlotDisplay tagSlot) {
            out.addProperty("tag", "#" + tagSlot.tag().location());
        }
        JsonArray items = new JsonArray();
        List<ItemStack> stacks = slot.resolveForStacks(ctx);
        Set<String> seen = new LinkedHashSet<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (seen.add(id) && seen.size() <= 16) items.add(id);
        }
        out.add("items", items);
        if (seen.size() > 16) out.addProperty("moreItems", seen.size() - 16);
        if (items.size() == 1) out.addProperty("item", items.get(0).getAsString());
        return out;
    }

    private RecipeIndex() {}
}
