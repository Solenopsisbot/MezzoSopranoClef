package dev.mezzo.clef.world;

import dev.mezzo.clef.api.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Resolves the {@code ids} argument of {@code findBlocks} into a fast {@link BlockState} predicate.
 *
 * <p>Three spellings are accepted, because "any log" is the question a bot actually asks:</p>
 * <ul>
 *   <li>{@code minecraft:oak_log} — a fully-qualified block id.</li>
 *   <li>{@code oak_log} — a bare path. Resolved against {@code minecraft:} first, then against any
 *       other namespace that has exactly that path (so modded blocks work without ceremony).</li>
 *   <li>{@code #minecraft:logs} / {@code #logs} — a block <i>tag</i>. Tag membership is whatever the
 *       connected server synced to us, so this reflects the running world, not a hardcoded table.</li>
 * </ul>
 *
 * <p>Matching is a couple of reference/identity comparisons per block, which matters: a 32-block
 * radius scan tests hundreds of thousands of states.</p>
 */
public final class BlockFilter {

    private final Set<Block> blocks;
    private final List<TagKey<Block>> tags;

    private BlockFilter(Set<Block> blocks, List<TagKey<Block>> tags) {
        this.blocks = blocks;
        this.tags = tags;
    }

    /** @throws ApiException {@code BAD_ARGS} if an id resolves to nothing in the running registry. */
    public static BlockFilter parse(List<String> ids) {
        if (ids == null || ids.isEmpty()) throw ApiException.badArgs("'ids' must list at least one block id or #tag");
        Set<Block> blocks = new LinkedHashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String raw : ids) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty()) throw ApiException.badArgs("'ids' contains an empty entry");
            if (id.startsWith("#")) {
                tags.add(TagKey.create(Registries.BLOCK, parseId(id.substring(1))));
            } else {
                blocks.add(resolveBlock(id));
            }
        }
        return new BlockFilter(blocks, tags);
    }

    /** True if {@code state} is one of the requested blocks, or a member of one of the requested tags. */
    public boolean matches(BlockState state) {
        if (blocks.contains(state.getBlock())) return true;
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    private static ResourceLocation parseId(String id) {
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : ResourceLocation.tryBuild("minecraft", id);
        if (parsed == null) throw ApiException.badArgs("not a valid identifier: '" + id + "'");
        return parsed;
    }

    private static Block resolveBlock(String id) {
        ResourceLocation parsed = parseId(id);
        Block direct = BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null);
        if (direct != null) return direct;
        // Bare path that isn't vanilla: accept it if exactly one namespace provides it.
        if (id.indexOf(':') < 0) {
            Block only = null;
            for (ResourceLocation candidate : BuiltInRegistries.BLOCK.keySet()) {
                if (!candidate.getPath().equals(id)) continue;
                if (only != null) {
                    throw ApiException.badArgs("ambiguous block '" + id + "' — qualify it with a namespace");
                }
                only = BuiltInRegistries.BLOCK.getOptional(candidate).orElse(null);
            }
            if (only != null) return only;
        }
        throw ApiException.badArgs("unknown block: '" + id + "'");
    }
}
