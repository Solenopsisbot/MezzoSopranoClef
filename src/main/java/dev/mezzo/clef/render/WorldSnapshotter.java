package dev.mezzo.clef.render;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the live client world into pure render inputs the CPU raycaster can chew on. Must run
 * on the client thread (reads block states + entities).
 *
 * <p>Performance: blocks are read <b>chunk by chunk</b> via {@link WorldChunk#getBlockState} so we
 * pay the chunk lookup once per 16×16 column instead of once per block (the old per-block
 * {@code world.getBlockState} stalled the tick). Empty chunks are skipped wholesale.
 */
public final class WorldSnapshotter {

    public static ArrayVoxelView snapshotBlocks(ClientWorld world, double cx, double cy, double cz, int radius) {
        int worldBottom = world.getBottomY();
        int worldTop = world.getBottomY() + world.getHeight() - 1;
        SnapshotBounds b = SnapshotBounds.compute(
                (int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz),
                radius, worldBottom, worldTop);
        int minX = b.minX(), minY = b.minY(), minZ = b.minZ();
        int sizeX = b.sizeX(), sizeY = b.sizeY(), sizeZ = b.sizeZ();
        int maxY = minY + sizeY - 1;

        ArrayVoxelView view = new ArrayVoxelView(minX, minY, minZ, sizeX, sizeY, sizeZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();

        int chunkXMin = minX >> 4, chunkXMax = (minX + sizeX - 1) >> 4;
        int chunkZMin = minZ >> 4, chunkZMax = (minZ + sizeZ - 1) >> 4;

        for (int cxk = chunkXMin; cxk <= chunkXMax; cxk++) {
            for (int czk = chunkZMin; czk <= chunkZMax; czk++) {
                WorldChunk chunk = world.getChunk(cxk, czk);
                if (chunk == null || chunk.isEmpty()) continue;
                int x0 = Math.max(minX, cxk << 4), x1 = Math.min(minX + sizeX - 1, (cxk << 4) + 15);
                int z0 = Math.max(minZ, czk << 4), z1 = Math.min(minZ + sizeZ - 1, (czk << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (state.isAir()) continue;
                            MapColor mc = state.getMapColor(world, pos);
                            if (mc == null || mc == MapColor.CLEAR) continue;
                            view.set(x, y, z, 0xFF000000 | (mc.color & 0xFFFFFF));
                        }
                    }
                }
            }
        }
        return view;
    }

    /** Nearby entities as colored boxes (so screenshots show players/mobs/items). Skips {@code self}. */
    public static List<EntityBox> snapshotEntities(ClientWorld world, Entity self,
                                                   double cx, double cy, double cz,
                                                   int radius, int maxEntities) {
        List<EntityBox> boxes = new ArrayList<>();
        double r2 = (double) radius * radius;
        for (Entity e : world.getEntities()) {
            if (e == self || !e.isAlive()) continue;
            if (e.squaredDistanceTo(cx, cy, cz) > r2) continue;
            Box bb = e.getBoundingBox();
            boxes.add(new EntityBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, colorFor(e)));
            if (boxes.size() >= maxEntities) break;
        }
        return boxes;
    }

    private static int colorFor(Entity e) {
        if (e instanceof PlayerEntity) return 0xFFE6E6FF; // players: near-white blue
        if (e instanceof HostileEntity) return 0xFFE03030; // hostiles: red
        if (e instanceof AnimalEntity) return 0xFF67C04A;  // animals: green
        if (e instanceof ItemEntity) return 0xFFFFD040;    // items: yellow
        return 0xFFB0B0B0;                                 // everything else: gray
    }

    /** Real biome/time-of-day sky tint, falling back to a pleasant default. */
    public static int skyColor(ClientWorld world, Vec3d cameraPos) {
        try {
            return 0xFF000000 | (world.getSkyColor(cameraPos, 1.0f) & 0xFFFFFF);
        } catch (Throwable t) {
            return 0xFF87CEEB;
        }
    }

    private WorldSnapshotter() {}
}
