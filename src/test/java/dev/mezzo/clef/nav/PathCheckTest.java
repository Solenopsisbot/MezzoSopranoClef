package dev.mezzo.clef.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code nav.check}'s search, against hand-drawn worlds. The point of these is that the movement
 * rules are the thing most likely to be quietly wrong — a step-up that ignores headroom, or a fall
 * limit that lets the bot claim it can reach the bottom of a ravine.
 */
class PathCheckTest {

    /** A flat stone floor at y=0 with optional walls, addressed as {@code (x,z)} strings. */
    private static final class FlatWorld implements WalkGrid {
        private final java.util.Set<String> walls = new java.util.HashSet<>();
        private final java.util.Map<String, Integer> floorHeight = new java.util.HashMap<>();
        private int defaultFloor = 0;
        private int limit = 32;

        FlatWorld wall(int x, int z) {
            walls.add(x + "," + z);
            return this;
        }

        FlatWorld floor(int x, int z, int y) {
            floorHeight.put(x + "," + z, y);
            return this;
        }

        FlatWorld limit(int blocks) {
            this.limit = blocks;
            return this;
        }

        private int floorAt(int x, int z) {
            return floorHeight.getOrDefault(x + "," + z, defaultFloor);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            if (walls.contains(x + "," + z)) return false;   // a full-height wall column
            return y > floorAt(x, z);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            if (walls.contains(x + "," + z)) return false;
            return y == floorAt(x, z);
        }

        @Override
        public boolean known(int x, int z) {
            return Math.abs(x) <= limit && Math.abs(z) <= limit;
        }
    }

    @Test
    void findsAStraightLineAcrossOpenGround() {
        PathCheck.Result result = PathCheck.search(new FlatWorld(), 0, 1, 0, 5, 1, 0, 0);
        assertTrue(result.reachable());
        assertEquals(5.0, result.cost(), 1e-9, "five level steps should cost five");
    }

    @Test
    void routesAroundAWall() {
        // A wall across x=2 for z in [-1,1] forces a detour, so the route must cost more than 4.
        FlatWorld world = new FlatWorld().wall(2, -1).wall(2, 0).wall(2, 1);
        PathCheck.Result result = PathCheck.search(world, 0, 1, 0, 4, 1, 0, 0);
        assertTrue(result.reachable());
        assertTrue(result.cost() > 4.0, "detour should cost more than the blocked straight line");
    }

    @Test
    void refusesToEnterUnknownChunks() {
        // The goal is outside the "loaded" area, so no route exists even though the ground is flat.
        PathCheck.Result result = PathCheck.search(new FlatWorld().limit(3), 0, 1, 0, 20, 1, 0, 0);
        assertFalse(result.reachable());
    }

    @Test
    void climbsOneBlockButNotTwo() {
        assertTrue(PathCheck.search(new FlatWorld().floor(1, 0, 1), 0, 1, 0, 1, 2, 0, 0).reachable(),
                "a one-block step up is walkable");
        assertFalse(PathCheck.search(new FlatWorld().floor(1, 0, 2), 0, 1, 0, 1, 3, 0, 0).reachable(),
                "a two-block wall is not");
    }

    @Test
    void fallsUpToTheLimitAndNoFurther() {
        int shallow = -PathCheck.MAX_FALL;
        int deep = -(PathCheck.MAX_FALL + 4);
        assertTrue(PathCheck.search(new FlatWorld().floor(1, 0, shallow), 0, 1, 0, 1, shallow + 1, 0, 0)
                .reachable(), "a drop within MAX_FALL is walkable");
        assertFalse(PathCheck.search(new FlatWorld().floor(1, 0, deep), 0, 1, 0, 1, deep + 1, 0, 0)
                .reachable(), "a cliff is not");
    }

    @Test
    void reachSlackLetsAGoalInsideAWallCount() {
        FlatWorld world = new FlatWorld().wall(3, 0);
        assertFalse(PathCheck.search(world, 0, 1, 0, 3, 1, 0, 0).reachable(),
                "standing inside the wall block itself is impossible");
        assertTrue(PathCheck.search(world, 0, 1, 0, 3, 1, 0, 1).reachable(),
                "but getting adjacent to it is fine");
    }

    @Test
    void reportsExhaustionRatherThanSearchingForever() {
        PathCheck.Result result = PathCheck.search(new FlatWorld().limit(1000),
                0, 1, 0, 900, 1, 900, 0, 50);
        assertFalse(result.reachable());
        assertTrue(result.exhausted(), "hitting the node budget must be distinguishable from 'no route'");
    }
}
