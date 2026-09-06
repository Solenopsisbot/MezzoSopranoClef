package dev.mezzo.clef.nav;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A small A* walkability search used by the {@code nav.check} command: <b>can the bot get there,
 * and roughly how expensive is it</b> — computed without moving, without touching Baritone, and
 * without waiting on a path calculation to finish.
 *
 * <h2>What this is and is not</h2>
 * This is deliberately <i>not</i> Baritone's cost model. Baritone considers block breaking, block
 * placing, parkour, elytra, and a much richer movement set; it will often find a route this search
 * calls unreachable. What this gives you is a cheap, synchronous, deterministic answer over the
 * chunks the client currently holds, using plain survival walking:
 *
 * <ul>
 *   <li>four-way horizontal steps (no diagonals — they'd need corner-cut checks for little gain),</li>
 *   <li>a one-block step up, if there's headroom,</li>
 *   <li>a fall of up to {@link #MAX_FALL} blocks.</li>
 * </ul>
 *
 * <p>Cost is in "walk-equivalent blocks": 1 per step, plus a small surcharge for climbing and for
 * falling, so a flat route and a staircase route of the same length aren't reported as identical.
 * Treat {@code cost} as a comparable estimate, not as ticks.</p>
 *
 * <p>The search is bounded by {@link #DEFAULT_MAX_NODES} expansions so a request into unloaded or
 * enclosed terrain returns "unreachable" promptly instead of walking the whole cache.</p>
 */
public final class PathCheck {

    /** Blocks a player may drop without the search calling it a cliff. */
    public static final int MAX_FALL = 3;
    /** Expansion budget. ~20k nodes is a few milliseconds and covers a couple hundred blocks of open terrain. */
    public static final int DEFAULT_MAX_NODES = 20_000;

    private static final double STEP_COST = 1.0;
    private static final double CLIMB_SURCHARGE = 0.5;
    private static final double FALL_SURCHARGE_PER_BLOCK = 0.3;

    /**
     * @param reachable whether a route was found within the node budget
     * @param cost      walk-equivalent cost of that route ({@code 0} when unreachable)
     * @param nodes     nodes expanded, so a caller can tell "no route" from "gave up"
     * @param exhausted true if the search hit the node budget rather than exploring everything
     */
    public record Result(boolean reachable, double cost, int nodes, boolean exhausted) {}

    /**
     * Searches from the block the bot is standing on to within {@code reach} blocks of the goal.
     *
     * @param reach horizontal/vertical slack around the goal; {@code 1} means "the goal block itself
     *              or any block touching it", matching {@code goto}'s {@code reach} argument
     */
    public static Result search(WalkGrid grid, int startX, int startY, int startZ,
                                int goalX, int goalY, int goalZ, int reach, int maxNodes) {
        int budget = maxNodes > 0 ? maxNodes : DEFAULT_MAX_NODES;

        // The bot may be standing mid-air (falling) or in a spot the grid calls non-standable;
        // snap down to the first standable support so the search starts somewhere legal.
        int sy = snapToGround(grid, startX, startY, startZ);
        if (sy == Integer.MIN_VALUE) return new Result(false, 0, 0, false);
        long startKey = key(startX, sy, startZ);

        Map<Long, Double> bestCost = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        bestCost.put(startKey, 0.0);
        open.add(new Node(startX, sy, startZ, 0.0, heuristic(startX, sy, startZ, goalX, goalY, goalZ)));

        int expanded = 0;
        while (!open.isEmpty()) {
            Node current = open.poll();
            long currentKey = key(current.x, current.y, current.z);
            Double known = bestCost.get(currentKey);
            if (known == null || current.g > known + 1e-9) continue;  // stale heap entry

            if (withinReach(current.x, current.y, current.z, goalX, goalY, goalZ, reach)) {
                return new Result(true, current.g, expanded, false);
            }
            if (++expanded > budget) return new Result(false, 0, expanded, true);

            for (int[] dir : HORIZONTAL) {
                int nx = current.x + dir[0], nz = current.z + dir[1];
                if (!grid.known(nx, nz)) continue;
                for (Step step : steps(grid, current.x, current.y, current.z, nx, nz)) {
                    long neighbourKey = key(nx, step.y, nz);
                    double g = current.g + step.cost;
                    Double prior = bestCost.get(neighbourKey);
                    if (prior != null && prior <= g + 1e-9) continue;
                    bestCost.put(neighbourKey, g);
                    open.add(new Node(nx, step.y, nz, g, g + heuristic(nx, step.y, nz, goalX, goalY, goalZ)));
                }
            }
        }
        return new Result(false, 0, expanded, false);
    }

    public static Result search(WalkGrid grid, int startX, int startY, int startZ,
                                int goalX, int goalY, int goalZ, int reach) {
        return search(grid, startX, startY, startZ, goalX, goalY, goalZ, reach, DEFAULT_MAX_NODES);
    }

    // ---- movement rules ---------------------------------------------------------------

    private record Step(int y, double cost) {}

    /** Legal landings when moving from (x,y,z) into the column (nx,nz): step up, level, or fall. */
    private static Iterable<Step> steps(WalkGrid grid, int x, int y, int z, int nx, int nz) {
        ArrayDeque<Step> out = new ArrayDeque<>(2);
        // Step up one: needs a third block of headroom above where we currently stand.
        if (grid.standable(nx, y, nz) && grid.passable(nx, y + 1, nz) && grid.passable(nx, y + 2, nz)
                && grid.passable(x, y + 2, z)) {
            out.add(new Step(y + 1, STEP_COST + CLIMB_SURCHARGE));
            return out;
        }
        if (!grid.passable(nx, y, nz) || !grid.passable(nx, y + 1, nz)) return out;   // wall
        if (grid.standable(nx, y - 1, nz)) {
            out.add(new Step(y, STEP_COST));
            return out;
        }
        for (int drop = 2; drop <= MAX_FALL + 1; drop++) {
            if (!grid.passable(nx, y - drop + 1, nz)) break;
            if (grid.standable(nx, y - drop, nz)) {
                out.add(new Step(y - drop + 1, STEP_COST + (drop - 1) * FALL_SURCHARGE_PER_BLOCK));
                return out;
            }
        }
        return out;
    }

    /** Walks down from (x,y,z) to the first block with support under it. */
    private static int snapToGround(WalkGrid grid, int x, int y, int z) {
        for (int probe = y; probe >= y - 4; probe--) {
            if (grid.standable(x, probe - 1, z) && grid.passable(x, probe, z) && grid.passable(x, probe + 1, z)) {
                return probe;
            }
        }
        return grid.passable(x, y, z) ? y : Integer.MIN_VALUE;
    }

    private static boolean withinReach(int x, int y, int z, int gx, int gy, int gz, int reach) {
        int r = Math.max(0, reach);
        return Math.abs(x - gx) <= r && Math.abs(y - gy) <= r && Math.abs(z - gz) <= r;
    }

    /** Admissible: straight-line block distance, never over-estimating the 1-per-step cost. */
    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy) * 0.5;
    }

    private static final int[][] HORIZONTAL = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    /** Packs a block position into a long key (26/12/26 bits — the full vanilla world range). */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private record Node(int x, int y, int z, double g, double f) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Double.compare(f, other.f);
        }
    }

    private PathCheck() {}
}
