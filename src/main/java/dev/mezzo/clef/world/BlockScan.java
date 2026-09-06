package dev.mezzo.clef.world;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bulk reads of the client's loaded chunk cache — the difference between "find a tree" being one
 * round-trip and being 270,000 of them.
 *
 * <p>Both entry points must run on the client thread (they read live chunks). Neither touches
 * chunks the server hasn't sent: {@code findBlocks} silently skips them, {@code blocksIn} reports
 * them as the {@link #UNLOADED} palette entry so a caller can tell "there is nothing there" apart
 * from "I cannot see there".</p>
 *
 * <h2>Why this is fast</h2>
 * A radius-32 sphere is ~275k blocks. Rather than 275k {@code world.getBlockState} calls (each a
 * chunk lookup plus a section lookup), we walk chunk sections directly and ask each section's
 * paletted container {@link ChunkSection#hasAny} whether it contains <i>any</i> matching state.
 * A section whose 16³ of blocks is all stone and air answers that from a two-entry palette, so the
 * overwhelming majority of the volume is rejected without ever being iterated.
 */
public final class BlockScan {

    /** Palette entry used by {@link #region} for blocks in chunks the server has not sent us. */
    public static final String UNLOADED = "unloaded";

    /** One matching block. {@code distance} is from the query centre, in blocks. */
    public record Hit(int x, int y, int z, String block, double distance) {}

    /**
     * A dense cuboid readout. {@code indices} is x-major (see {@link PaletteCodec#index}) and each
     * value indexes {@code palette}.
     */
    public record Region(List<String> palette, int[] indices, int sizeX, int sizeY, int sizeZ) {}

    /**
     * Nearest-first search of the loaded chunk cache.
     *
     * @param radius  half-size of the cube searched around ({@code cx},{@code cy},{@code cz})
     * @param max     hard cap on returned hits; memory stays O(max) regardless of how many match
     * @param nearest true to return the {@code max} closest hits, false to return the first
     *                {@code max} found in scan order (cheaper: stops as soon as it's full)
     */
    public static List<Hit> find(ClientWorld world, BlockFilter filter,
                                 double cx, double cy, double cz,
                                 int radius, int max, boolean nearest) {
        if (max <= 0) return List.of();
        int centerX = (int) Math.floor(cx), centerY = (int) Math.floor(cy), centerZ = (int) Math.floor(cz);
        int minX = centerX - radius, maxX = centerX + radius;
        int minZ = centerZ - radius, maxZ = centerZ + radius;
        int minY = Math.max(world.getBottomY(), centerY - radius);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 1, centerY + radius);

        // Max-heap on distance: the worst kept hit sits on top, so it's the one we evict.
        PriorityQueue<Hit> best = new PriorityQueue<>(Comparator.comparingDouble(Hit::distance).reversed());
        List<Hit> firstFound = new ArrayList<>();
        double radius2 = (double) radius * radius;

        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                WorldChunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;                    // not sent by the server yet
                ChunkSection[] sections = chunk.getSectionArray();
                int chunkBottom = chunk.getBottomY();
                int x0 = Math.max(minX, chunkX << 4), x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4), z1 = Math.min(maxZ, (chunkZ << 4) + 15);

                for (int s = 0; s < sections.length; s++) {
                    int sectionBottom = chunkBottom + (s << 4);
                    int y0 = Math.max(minY, sectionBottom), y1 = Math.min(maxY, sectionBottom + 15);
                    if (y0 > y1) continue;
                    ChunkSection section = sections[s];
                    if (section == null || !section.hasAny(filter::matches)) continue;  // palette-level reject

                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int y = y0; y <= y1; y++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!filter.matches(state)) continue;
                                double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
                                double d2 = dx * dx + dy * dy + dz * dz;
                                if (d2 > radius2) continue;     // cube scan, spherical result
                                Hit hit = new Hit(x, y, z,
                                        Registries.BLOCK.getId(state.getBlock()).toString(), Math.sqrt(d2));
                                if (!nearest) {
                                    firstFound.add(hit);
                                    if (firstFound.size() >= max) return firstFound;
                                } else if (best.size() < max) {
                                    best.add(hit);
                                } else if (best.peek().distance() > hit.distance()) {
                                    best.poll();
                                    best.add(hit);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!nearest) return firstFound;
        List<Hit> out = new ArrayList<>(best);
        out.sort(Comparator.comparingDouble(Hit::distance));
        return out;
    }

    /** Dense readout of an inclusive block cuboid. Caller is responsible for capping the volume. */
    public static Region region(ClientWorld world, int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ) {
        int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;
        int[] indices = new int[sizeX * sizeY * sizeZ];
        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();

        int worldBottom = world.getBottomY();
        int worldTop = worldBottom + world.getHeight() - 1;

        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                WorldChunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                int x0 = Math.max(minX, chunkX << 4), x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4), z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                ChunkSection[] sections = chunk == null ? null : chunk.getSectionArray();
                int chunkBottom = chunk == null ? worldBottom : chunk.getBottomY();

                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            String id;
                            if (sections == null || y < worldBottom || y > worldTop) {
                                id = UNLOADED;
                            } else {
                                int s = (y - chunkBottom) >> 4;
                                ChunkSection section = s >= 0 && s < sections.length ? sections[s] : null;
                                id = section == null ? UNLOADED
                                        : Registries.BLOCK.getId(
                                                section.getBlockState(x & 15, y & 15, z & 15).getBlock()).toString();
                            }
                            Integer idx = paletteIndex.get(id);
                            if (idx == null) {
                                idx = palette.size();
                                palette.add(id);
                                paletteIndex.put(id, idx);
                            }
                            indices[PaletteCodec.index(x - minX, y - minY, z - minZ, sizeY, sizeZ)] = idx;
                        }
                    }
                }
            }
        }
        return new Region(palette, indices, sizeX, sizeY, sizeZ);
    }

    private BlockScan() {}
}
