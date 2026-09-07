package dev.mezzo.clef.nav;

/**
 * The only thing {@link PathCheck} needs to know about a world: which blocks a walking player can
 * stand on and which they can move through. Minecraft-free on purpose, so the search can be
 * unit-tested against hand-drawn worlds.
 */
public interface WalkGrid {

    /** True if a player's body can occupy (x,y,z) — air, grass, water, a torch, an open door. */
    boolean passable(int x, int y, int z);

    /** True if (x,y,z) is a full block a player can stand on top of. */
    boolean standable(int x, int y, int z);

    /** True if the column at (x,z) is inside a chunk we have actually been sent. */
    boolean known(int x, int z);
}
