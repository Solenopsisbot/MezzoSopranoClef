package dev.mezzo.clef.bot;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * How fast a nearby entity is actually moving, in blocks per tick.
 *
 * <p>The obvious answer, {@code Entity.getVelocity()}, is the wrong one for almost everything a bot
 * cares about. On the client that field is only written when the server explicitly pushes a
 * velocity (knockback, an explosion, a launched projectile). A zombie walking towards you, or an
 * ender dragon crossing the island, arrives as a stream of position updates and leaves
 * {@code getVelocity()} sitting at zero — so anything that leads a target using it aims exactly
 * where the target already is.</p>
 *
 * <p>What the client does know is where the entity was at the start of this tick.
 * {@code ClientWorld.tickEntity} calls {@code Entity.resetPosition()} before ticking it, which
 * snapshots the position into {@code lastX/lastY/lastZ}. Read after the tick — which is when the
 * mod's per-tick hooks run — the difference between that and the current position <i>is</i> the
 * motion the client observed, interpolation and all.</p>
 */
public final class EntityMotion {

    /** Below this, the entity moved by floating-point noise rather than by walking. */
    private static final double MOVING_EPSILON = 1.0e-9;

    /**
     * Faster than any mob travels under its own power (an arrow only does 3 b/t). A step this big
     * is a teleport or a position resync, not motion, and extrapolating from it would throw an
     * arrow at nothing.
     */
    private static final double TELEPORT_BLOCKS_PER_TICK = 4.0;

    /**
     * Blocks per tick, as the client saw it move over the last tick.
     *
     * <p>Falls back to {@code getVelocity()} when there is no usable displacement — the entity's
     * first tick after spawning, or a jump big enough to be a teleport. For client-simulated things
     * (projectiles, dropped items, the local player) the two agree anyway.</p>
     */
    public static Vec3d of(Entity entity) {
        Vec3d observed = entity.getPos().subtract(entity.lastX, entity.lastY, entity.lastZ);
        double squared = observed.lengthSquared();
        if (squared <= MOVING_EPSILON || squared > TELEPORT_BLOCKS_PER_TICK * TELEPORT_BLOCKS_PER_TICK) {
            return entity.getVelocity();
        }
        return observed;
    }

    private EntityMotion() {}
}
