package dev.mezzo.clef.nav;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * {@link WalkGrid} backed by the client's loaded chunks. Must be built and used on the client
 * thread. Chunks the server hasn't sent read as unknown, so {@link PathCheck} refuses to route
 * through terrain it can't actually see rather than optimistically assuming open air.
 */
public final class WorldWalkGrid implements WalkGrid {

    private final ClientWorld world;
    private final int bottomY;
    private final int topY;
    private final BlockPos.Mutable scratch = new BlockPos.Mutable();

    public WorldWalkGrid(ClientWorld world) {
        this.world = world;
        this.bottomY = world.getBottomY();
        this.topY = world.getBottomY() + world.getHeight() - 1;
    }

    @Override
    public boolean passable(int x, int y, int z) {
        if (y < bottomY) return false;
        if (y > topY) return true;                    // above the build limit is open sky
        BlockState state = stateAt(x, y, z);
        if (state.isAir()) return true;   // the overwhelmingly common case; skip the shape lookup
        // getCollisionShape() would be exact but builds a VoxelShape per probe; for a walkability
        // estimate "no collision box at all" is the honest cheap test, and it treats torches, grass,
        // signs and water as walk-through the way a survival player experiences them.
        return state.getCollisionShape(world, scratch.set(x, y, z)).isEmpty();
    }

    @Override
    public boolean standable(int x, int y, int z) {
        if (y < bottomY || y > topY) return false;
        BlockState state = stateAt(x, y, z);
        if (state.isAir()) return false;
        return state.isSideSolidFullSquare(world, scratch.set(x, y, z), net.minecraft.util.math.Direction.UP);
    }

    @Override
    public boolean known(int x, int z) {
        return world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    private BlockState stateAt(int x, int y, int z) {
        WorldChunk chunk = world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return net.minecraft.block.Blocks.BEDROCK.getDefaultState(); // unknown = impassable
        return chunk.getBlockState(scratch.set(x, y, z));
    }
}
