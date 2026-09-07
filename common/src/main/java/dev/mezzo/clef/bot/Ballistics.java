package dev.mezzo.clef.bot;

/**
 * Arrow ballistics, in Minecraft's own units — blocks and ticks.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested without a game. The constants
 * are read off the 1.21.8 client:</p>
 * <ul>
 *   <li>{@code PersistentProjectileEntity.tick()} moves the arrow by its <i>current</i> velocity,
 *       <i>then</i> multiplies that velocity by 0.99 (air drag), <i>then</i> subtracts
 *       {@code getGravity()} = 0.05 from the Y component. The order matters: swapping the move and
 *       the gravity step shifts the impact point by nearly a block at 40 blocks range.</li>
 *   <li>{@code BowItem} looses at {@code getPullProgress(drawTicks) * 3.0} blocks per tick, so a
 *       fully drawn bow starts at 3 b/t = 60 blocks per second.</li>
 * </ul>
 *
 * <h2>Why simulate instead of using the closed form</h2>
 * Drop is usually quoted as {@code 10*t²} for {@code t} in seconds (gravity of 20 b/s²), which is
 * the drag-free answer. Past roughly 25 blocks it is visibly wrong: drag slows the arrow, so it
 * spends longer in the air and falls further than the formula says, and the error is one-sided —
 * every shot lands low. Rather than invert a formula that is missing a term, this integrates the
 * same loop the arrow will actually fly and searches for the launch angle that passes through the
 * target.
 *
 * <p>The search is a coarse 1° scan for the bracket followed by bisection. It costs a few hundred
 * arithmetic-only tick simulations — microseconds, a few times per arrow — and it cannot be fooled
 * by the two-solution shape of the problem: scanning upwards from the steepest angle always finds
 * the <b>flat</b> arc first, which is the one that gets there soonest and is hardest to dodge.</p>
 */
public final class Ballistics {

    /** Blocks per tick², from {@code PersistentProjectileEntity.getGravity()}. */
    public static final double GRAVITY = 0.05;
    /** Velocity multiplier per tick in air, from {@code PersistentProjectileEntity.tick()}. */
    public static final double DRAG = 0.99;
    /** Launch speed of a fully drawn bow, in blocks per tick. */
    public static final double BOW_MAX_SPEED = 3.0;

    /** 10 seconds of flight. Nothing worth shooting is further away, and it bounds the search. */
    private static final int MAX_FLIGHT_TICKS = 200;
    private static final double SCAN_MIN_DEGREES = -89.0;
    private static final double SCAN_MAX_DEGREES = 89.0;
    private static final double SCAN_STEP_DEGREES = 1.0;
    private static final int BISECTION_STEPS = 24;
    /** Stop bisecting once the bracket is this tight; well under the client's rotation precision. */
    private static final double ANGLE_EPSILON = 1.0e-4;

    /**
     * A solved shot.
     *
     * @param elevationDegrees launch elevation, <b>positive is upwards</b>. Minecraft's pitch is
     *                         the negation of this ({@code pitch = -elevation}).
     * @param flightTicks      ticks between release and crossing the target's horizontal distance
     */
    public record Aim(double elevationDegrees, double flightTicks) {}

    /**
     * Bow draw progress, mirroring {@code BowItem.getPullProgress}. Full power at 20 ticks.
     *
     * @param drawTicks how long the bow has been held, in ticks
     * @return 0..1
     */
    public static double pullProgress(int drawTicks) {
        double f = Math.max(0, drawTicks) / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        return Math.min(f, 1.0);
    }

    /** Launch speed in blocks per tick for a bow held {@code drawTicks} ticks. */
    public static double bowSpeed(int drawTicks) {
        return pullProgress(drawTicks) * BOW_MAX_SPEED;
    }

    /**
     * Finds the flat-arc launch angle that puts an arrow through a point.
     *
     * @param horizontal horizontal distance to the target, in blocks (must be positive)
     * @param dy         target height minus launch height, in blocks (negative = below)
     * @param speed      launch speed in blocks per tick (see {@link #bowSpeed(int)})
     * @return the solution, or {@code null} when no angle reaches the point — too far, or too far
     *         above. Callers should treat that as "don't loose this arrow", not as an error.
     */
    public static Aim solve(double horizontal, double dy, double speed) {
        if (!(horizontal > 0) || !(speed > 0)) return null;
        double below = Double.NaN;      // steepest angle scanned that still fell short of the target
        for (double angle = SCAN_MIN_DEGREES; angle <= SCAN_MAX_DEGREES; angle += SCAN_STEP_DEGREES) {
            Shot shot = fly(horizontal, speed, angle);
            if (shot == null) continue; // this angle never covers the distance at all
            if (shot.height() >= dy) {
                // The first angle that clears the target brackets the answer with the previous one.
                // If there is no previous one, the flattest arc that reaches at all already passes
                // above: the target is below us and close, and this angle is as good as it gets.
                return Double.isNaN(below) ? new Aim(angle, shot.ticks())
                        : refine(horizontal, dy, speed, below, angle);
            }
            below = angle;
        }
        return null;
    }

    /** Bisects between an angle that falls short and one that clears, and returns the boundary. */
    private static Aim refine(double horizontal, double dy, double speed, double low, double high) {
        Shot best = fly(horizontal, speed, high);
        for (int i = 0; i < BISECTION_STEPS && high - low > ANGLE_EPSILON; i++) {
            double mid = (low + high) / 2.0;
            Shot shot = fly(horizontal, speed, mid);
            if (shot == null || shot.height() < dy) {
                low = mid;
            } else {
                high = mid;
                best = shot;
            }
        }
        return best == null ? null : new Aim(high, best.ticks());
    }

    /** Where the arrow is, and when, as it crosses {@code horizontal}. Null if it never does. */
    private record Shot(double height, double ticks) {}

    private static Shot fly(double horizontal, double speed, double elevationDegrees) {
        double theta = Math.toRadians(elevationDegrees);
        double vx = Math.cos(theta) * speed;    // positive for every angle we scan
        double vy = Math.sin(theta) * speed;
        double x = 0.0;
        double y = 0.0;
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            // Drag is geometric, so the whole remaining horizontal distance is bounded by
            // vx/(1-0.99) = vx*100. Most of the angle scan is steep shots that will never cover the
            // ground; without this they each cost the full flight simulation to find that out.
            if (x + vx * (1.0 / (1.0 - DRAG)) < horizontal) return null;
            double nextX = x + vx;
            double nextY = y + vy;
            if (nextX >= horizontal) {
                // Land between ticks: the arrow doesn't stop at tick boundaries, and at 3 b/t a
                // whole tick is three blocks of error.
                double fraction = (horizontal - x) / (nextX - x);
                return new Shot(y + fraction * (nextY - y), tick - 1 + fraction);
            }
            x = nextX;
            y = nextY;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return null;
    }

    private Ballistics() {}
}
