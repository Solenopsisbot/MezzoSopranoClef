package dev.mezzo.clef.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.MultiPlayerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bridges the live client world into pure render inputs the CPU raycaster can chew on. Must run
 * on the client thread (reads block states + entities).
 *
 * <p>Performance: blocks are read <b>chunk by chunk</b> via {@link LevelChunk#getBlockState} so we
 * pay the chunk lookup once per 16×16 column instead of once per block (the old per-block
 * {@code world.getBlockState} stalled the tick). Empty chunks are skipped wholesale.
 */
public final class WorldSnapshotter {

    public static ArrayVoxelView snapshotBlocks(MultiPlayerLevel world, double cx, double cy, double cz, int radius) {
        int worldBottom = 0;                       // worlds start at y=0 until 1.18
        int worldTop = world.getHeight() - 1;
        SnapshotBounds b = SnapshotBounds.compute(
                (int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz),
                radius, worldBottom, worldTop);
        int minX = b.minX(), minY = b.minY(), minZ = b.minZ();
        int sizeX = b.sizeX(), sizeY = b.sizeY(), sizeZ = b.sizeZ();
        int maxY = minY + sizeY - 1;

        ArrayVoxelView view = new ArrayVoxelView(minX, minY, minZ, sizeX, sizeY, sizeZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int chunkXMin = minX >> 4, chunkXMax = (minX + sizeX - 1) >> 4;
        int chunkZMin = minZ >> 4, chunkZMax = (minZ + sizeZ - 1) >> 4;

        for (int cxk = chunkXMin; cxk <= chunkXMax; cxk++) {
            for (int czk = chunkZMin; czk <= chunkZMax; czk++) {
                LevelChunk chunk = world.getChunk(cxk, czk);
                if (chunk == null || chunk.isEmpty()) continue;
                int x0 = Math.max(minX, cxk << 4), x1 = Math.min(minX + sizeX - 1, (cxk << 4) + 15);
                int z0 = Math.max(minZ, czk << 4), z1 = Math.min(minZ + sizeZ - 1, (czk << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (state.isAir()) continue;
                            MaterialColor mc = state.getMapColor(world, pos);
                            if (mc == null || mc == MaterialColor.NONE) continue;
                            // x/z are clamped to [x0,x1]⊆region and y to [minY,maxY]=region, so the
                            // voxel is provably in-bounds — skip the redundant per-block range check.
                            view.setUnchecked(x, y, z, 0xFF000000 | (mc.col & 0xFFFFFF));
                        }
                    }
                }
            }
        }
        return view;
    }

    /** Nearby entities as colored boxes (so screenshots show players/mobs/items). Skips {@code self}. */
    public static List<EntityBox> snapshotEntities(MultiPlayerLevel world, Entity self,
                                                   double cx, double cy, double cz,
                                                   int radius, int maxEntities) {
        List<EntityBox> boxes = new ArrayList<>();
        double r2 = (double) radius * radius;
        for (Entity e : world.entitiesForRendering()) {
            if (e == self || !e.isAlive()) continue;
            if (e.distanceToSqr(cx, cy, cz) > r2) continue;
            AABB bb = e.getBoundingBox();
            boxes.add(new EntityBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, colorFor(e)));
            if (boxes.size() >= maxEntities) break;
        }
        return boxes;
    }

    private static int colorFor(Entity e) {
        if (e instanceof Player) return 0xFFE6E6FF; // players: near-white blue
        if (e instanceof Monster) return 0xFFE03030; // hostiles: red
        if (e instanceof Animal) return 0xFF67C04A;  // animals: green
        if (e instanceof ItemEntity) return 0xFFFFD040;    // items: yellow
        return 0xFFB0B0B0;                                 // everything else: gray
    }

    /** Real biome/time-of-day sky tint, falling back to a pleasant default. */
    public static int skyColor(MultiPlayerLevel world, Vec3 cameraPos) {
        try {
            // Returns normalised RGB components here rather than a packed int, keyed by BlockPos.
            Vec3 tint = world.getSkyColor(new BlockPos(cameraPos), 1.0f);
            return 0xFF000000 | (channel(tint.x) << 16) | (channel(tint.y) << 8) | channel(tint.z);
        } catch (Throwable t) {
            return 0xFF87CEEB;
        }
    }

    /** Clamps a 0..1 colour component to a 0..255 channel. */
    private static int channel(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v * 255.0)));
    }

    private WorldSnapshotter() {}
}
