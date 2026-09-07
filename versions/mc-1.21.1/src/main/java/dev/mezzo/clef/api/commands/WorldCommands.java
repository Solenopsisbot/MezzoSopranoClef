package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.nav.PathCheck;
import dev.mezzo.clef.nav.WorldWalkGrid;
import dev.mezzo.clef.world.BlockFilter;
import dev.mezzo.clef.world.BlockScan;
import dev.mezzo.clef.world.PaletteCodec;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bulk world queries: the commands that let an agent look around without spending a round-trip per
 * block. {@code blockAt} is fine for checking one thing; it is hopeless for "is there a tree near
 * me", which is why {@link BlockScan} exists and why these commands are the priority.
 *
 * <p>Everything here is read-only and runs on the client thread against the loaded chunk cache. The
 * limits in {@link ClefConfig.Queries} matter: these are the only commands that can do real work on
 * the tick thread, so their radius and volume arguments are clamped rather than trusted.</p>
 */
public final class WorldCommands {

    public static void registerAll(CommandDispatcher d) {

        d.register("findBlocks",
                "search loaded chunks for blocks or #tags {ids:[...], radius?=32, max?=32, sort?=nearest|none}",
                ctx -> {
                    List<String> ids = ctx.stringList("ids");
                    BlockFilter filter = BlockFilter.parse(ids);
                    ClefConfig.Queries limits = MezzoClef.config().queries;
                    int radius = clamp(ctx.i("radius", 32), 1, limits.maxFindRadius);
                    int max = clamp(ctx.i("max", 32), 1, limits.maxFindResults);
                    String sort = ctx.str("sort", "nearest");
                    if (!sort.equals("nearest") && !sort.equals("none")) {
                        throw ApiException.badArgs("sort must be 'nearest' or 'none'");
                    }
                    boolean nearest = sort.equals("nearest");
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.level == null || mc.player == null) throw ApiException.notInWorld();
                        List<BlockScan.Hit> hits = BlockScan.find(mc.level, filter,
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(), radius, max, nearest);
                        JsonArray arr = new JsonArray();
                        for (BlockScan.Hit hit : hits) {
                            JsonObject o = new JsonObject();
                            o.addProperty("x", hit.x());
                            o.addProperty("y", hit.y());
                            o.addProperty("z", hit.z());
                            o.addProperty("block", hit.block());
                            o.addProperty("distance", hit.distance());
                            arr.add(o);
                        }
                        return arr;
                    });
                });

        d.register("blocksIn",
                "dense readout of a block cuboid {minX,minY,minZ,maxX,maxY,maxZ, palette?=true}",
                ctx -> {
                    // Normalise: callers shouldn't have to remember which corner is "min".
                    int x0 = ctx.requireInt("minX"), x1 = ctx.requireInt("maxX");
                    int y0 = ctx.requireInt("minY"), y1 = ctx.requireInt("maxY");
                    int z0 = ctx.requireInt("minZ"), z1 = ctx.requireInt("maxZ");
                    int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
                    int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
                    int minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
                    boolean palette = ctx.bool("palette", true);

                    long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                    int budget = MezzoClef.config().queries.maxRegionVolume;
                    if (volume > budget) {
                        throw ApiException.badArgs("region of " + volume + " blocks exceeds the "
                                + budget + "-block limit (queries.maxRegionVolume)");
                    }
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.level == null) throw ApiException.notInWorld();
                        BlockScan.Region region = BlockScan.region(mc.level, minX, minY, minZ, maxX, maxY, maxZ);

                        JsonObject o = new JsonObject();
                        JsonArray size = new JsonArray();
                        size.add(region.sizeX());
                        size.add(region.sizeY());
                        size.add(region.sizeZ());
                        o.add("size", size);
                        JsonArray origin = new JsonArray();
                        origin.add(minX);
                        origin.add(minY);
                        origin.add(minZ);
                        o.add("origin", origin);
                        o.addProperty("order", "x-major: i = ((x-minX)*sizeY + (y-minY))*sizeZ + (z-minZ)");

                        if (palette) {
                            JsonArray ids = new JsonArray();
                            region.palette().forEach(ids::add);
                            o.add("palette", ids);
                            o.addProperty("encoding", "base64 LEB128 varint indices into palette");
                            o.addProperty("data", PaletteCodec.encode(region.indices()));
                        } else {
                            JsonArray blocks = new JsonArray();
                            for (int index : region.indices()) blocks.add(region.palette().get(index));
                            o.add("blocks", blocks);
                        }
                        return o;
                    });
                });

        d.register("target", "what is under the crosshair {maxDistance?=4.5, fluids?=false}", ctx -> {
            double maxDistance = ctx.d("maxDistance", 4.5);
            boolean fluids = ctx.bool("fluids", false);
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) throw ApiException.notInWorld();
                return raycast(mc, maxDistance, fluids);
            });
        });

        d.register("lookAt", "point the head at a block or an entity {x,y,z} | {entityId}", ctx -> {
            Integer entityId = ctx.has("entityId") ? ctx.i("entityId", -1) : null;
            boolean haveXyz = ctx.has("x") && ctx.has("y") && ctx.has("z");
            if (entityId == null && !haveXyz) throw ApiException.badArgs("give either {x,y,z} or {entityId}");
            double tx = ctx.d("x", 0), ty = ctx.d("y", 0), tz = ctx.d("z", 0);
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) throw ApiException.notInWorld();
                Vec3 target;
                if (entityId != null) {
                    Entity e = mc.level.getEntity(entityId);
                    if (e == null) throw ApiException.notFound("no entity with id " + entityId);
                    target = e.getEyePosition();
                } else {
                    // Bare block coordinates mean the block, so aim at its centre rather than a corner.
                    target = new Vec3(tx, ty, tz);
                    if (isWholeNumber(tx) && isWholeNumber(ty) && isWholeNumber(tz)) {
                        target = target.add(0.5, 0.5, 0.5);
                    }
                }
                Vec3 eye = mc.player.getEyePosition();
                double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
                float pitch = Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
                mc.player.setYRot(yaw);
                mc.player.setYHeadRot(yaw);
                mc.player.setYBodyRot(yaw);
                mc.player.setXRot(pitch);
                JsonObject o = new JsonObject();
                o.addProperty("yaw", yaw);
                o.addProperty("pitch", pitch);
                o.addProperty("distance", Math.sqrt(dx * dx + dy * dy + dz * dz));
                return o;
            });
        });

        d.register("nav.check",
                "cheap walkability ESTIMATE — a walk-only A* over loaded chunks, not Baritone. "
                        + "Nothing moves. {x,y,z,reach?=1,maxNodes?}",
                ctx -> {
                    int x = ctx.requireInt("x");
                    int y = ctx.requireInt("y");
                    int z = ctx.requireInt("z");
                    int reach = Math.max(0, ctx.i("reach", 1));
                    int budget = ctx.i("maxNodes", MezzoClef.config().queries.maxPathCheckNodes);
                    int maxNodes = clamp(budget, 100, MezzoClef.config().queries.maxPathCheckNodes);
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.level == null || mc.player == null) throw ApiException.notInWorld();
                        var grid = new WorldWalkGrid(mc.level);
                        var start = mc.player.blockPosition();
                        PathCheck.Result result = PathCheck.search(grid,
                                start.getX(), start.getY(), start.getZ(), x, y, z, reach, maxNodes);
                        JsonObject o = new JsonObject();
                        // Three outcomes, not two. Running out of budget is not the same as proving
                        // there is no route, and reporting it as `reachable:false` made callers give
                        // up on goals Baritone then walked to. Branch on `verdict`, not `reachable`.
                        String verdict = result.reachable() ? "reachable"
                                : result.exhausted() ? "unknown" : "unreachable";
                        o.addProperty("verdict", verdict);
                        o.addProperty("reachable", result.reachable());
                        if (result.reachable()) o.addProperty("cost", result.cost());
                        o.addProperty("nodes", result.nodes());
                        o.addProperty("maxNodes", maxNodes);
                        o.addProperty("exhausted", result.exhausted());
                        o.addProperty("model", "walk-only A* over loaded chunks (no breaking, placing, "
                                + "parkour or elytra) — an estimate, weaker than Baritone's planner");
                        return o;
                    });
                });

        d.register("registry",
                "ids the running version actually has {kinds?:[blocks,items,entities], tags?=false}",
                ctx -> {
                    var kinds = ctx.strings("kinds");
                    boolean tags = ctx.bool("tags", false);
                    JsonObject o = new JsonObject();
                    o.addProperty("minecraftVersion", net.minecraft.SharedConstants.getCurrentVersion().getName());
                    if (wants(kinds, "blocks")) o.add("blocks", ids(BuiltInRegistries.BLOCK));
                    if (wants(kinds, "items")) o.add("items", ids(BuiltInRegistries.ITEM));
                    if (wants(kinds, "entities")) o.add("entities", ids(BuiltInRegistries.ENTITY_TYPE));
                    if (tags) {
                        JsonObject allTags = new JsonObject();
                        if (wants(kinds, "blocks")) allTags.add("blocks", tagsOf(BuiltInRegistries.BLOCK));
                        if (wants(kinds, "items")) allTags.add("items", tagsOf(BuiltInRegistries.ITEM));
                        if (wants(kinds, "entities")) allTags.add("entities", tagsOf(BuiltInRegistries.ENTITY_TYPE));
                        o.add("tags", allTags);
                    }
                    return o;
                });
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Raycasts from the player's eyes the way vanilla picks a target, but on demand rather than as
     * a side effect of rendering. That distinction matters here: {@code MinecraftClient.crosshairTarget}
     * is refreshed by the render loop, which a headless bot never runs, so reading it would hand back
     * whatever was true the last time a frame was drawn — usually nothing at all.
     */
    private static JsonObject raycast(Minecraft mc, double maxDistance, boolean fluids) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(maxDistance));

        HitResult block = mc.player.pick(maxDistance, 0.0f, fluids);
        double blockDistance = block.getType() == HitResult.Type.MISS
                ? maxDistance : block.getLocation().distanceTo(eye);

        AABB sweep = mc.player.getBoundingBox().expandTowards(look.scale(maxDistance)).inflate(1.0);
        EntityHitResult entity = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                mc.player, eye, end, sweep,
                e -> !e.isSpectator() && e.isPickable(), blockDistance * blockDistance);

        JsonObject o = new JsonObject();
        if (entity != null) {
            Entity hit = entity.getEntity();
            o.addProperty("kind", "entity");
            o.addProperty("entityId", hit.getId());
            o.addProperty("type", EntityType.getKey(hit.getType()).toString());
            o.addProperty("name", hit.getName().getString());
            o.addProperty("x", entity.getLocation().x);
            o.addProperty("y", entity.getLocation().y);
            o.addProperty("z", entity.getLocation().z);
            o.addProperty("distance", entity.getLocation().distanceTo(eye));
            return o;
        }
        if (block instanceof BlockHitResult hit && block.getType() != HitResult.Type.MISS) {
            o.addProperty("kind", "block");
            o.addProperty("x", hit.getBlockPos().getX());
            o.addProperty("y", hit.getBlockPos().getY());
            o.addProperty("z", hit.getBlockPos().getZ());
            o.addProperty("face", hit.getDirection().getSerializedName());
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(
                    mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
            o.addProperty("distance", blockDistance);
            return o;
        }
        o.addProperty("kind", "none");
        return o;
    }

    private static boolean wants(java.util.Set<String> kinds, String kind) {
        return kinds == null || kinds.contains(kind);
    }

    private static JsonArray ids(Registry<?> registry) {
        JsonArray arr = new JsonArray();
        registry.keySet().stream().map(ResourceLocation::toString).sorted().forEach(arr::add);
        return arr;
    }

    /** {@code tag -> [member ids]} for every tag the server synced for this registry. */
    private static JsonObject tagsOf(Registry<?> registry) {
        JsonObject out = new JsonObject();
        // getTags() yields Pair<TagKey, HolderSet.Named> on this release; it only becomes a
        // HolderSet.Named with its own key() in 1.21.2.
        registry.getTags().forEach(pair -> {
            JsonArray members = new JsonArray();
            pair.getSecond().stream().forEach(entry -> entry.unwrapKey()
                    .ifPresent(key -> members.add(key.location().toString())));
            out.add("#" + pair.getFirst().location(), members);
        });
        return out;
    }

    private static boolean isWholeNumber(double v) {
        return v == Math.rint(v);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private WorldCommands() {}
}
