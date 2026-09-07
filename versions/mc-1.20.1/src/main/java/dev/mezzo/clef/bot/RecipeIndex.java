package dev.mezzo.clef.bot;

import dev.mezzo.clef.api.ApiException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Item-id resolution only.
 *
 * <p>The full {@code RecipeIndex} answers recipe questions through the recipe-display API, which
 * does not exist before 1.21.4 — see the crafting stubs alongside this. But {@code resolveItem} is
 * a plain registry lookup that the rest of the command surface uses, so it is kept here rather than
 * duplicated somewhere else.
 */
public final class RecipeIndex {

    public static Item resolveItem(String id) {
        String trimmed = id == null ? "" : id.trim();
        String qualified = trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
        ResourceLocation parsed;
        try {
            parsed = ResourceLocation.tryParse(qualified);
        } catch (Exception e) {
            parsed = null;
        }
        if (parsed == null) throw ApiException.badArgs("not a valid item id: '" + id + "'");
        return BuiltInRegistries.ITEM.getOptional(parsed)
                .orElseThrow(() -> ApiException.badArgs("unknown item: '" + id + "'"));
    }

    private RecipeIndex() {}
}
