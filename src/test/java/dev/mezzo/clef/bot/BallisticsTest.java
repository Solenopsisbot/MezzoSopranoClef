package dev.mezzo.clef.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one part of {@code shootAt} that can be checked without a running game — and the part that
 * was measurably wrong in the agent-side implementation this replaces, where the drop was
 * compensated at about half its real value and every arrow landed low.
 *
 * <p>The strongest test here is {@link #solutionsActuallyHitWhatTheyAimAt()}: it re-flies each
 * solved angle through an independent copy of the arrow integration and checks the arrow really
 * passes through the target. A solver that agrees with itself proves nothing; one that lands on the
 * point when simulated separately does.</p>
 */
class BallisticsTest {

    private static final double FULL_DRAW = Ballistics.BOW_MAX_SPEED;

    @Test
    void pullProgressMatchesTheBow() {
        // BowItem.getPullProgress: (t² + 2t)/3 for t in seconds, capped at 1.
        assertEquals(0.0, Ballistics.pullProgress(0), 1e-9);
        assertEquals(1.0, Ballistics.pullProgress(20), 1e-9);
        assertEquals(1.0, Ballistics.pullProgress(40), 1e-9, "over-drawing does not overshoot");
        assertEquals((0.25 * 0.25 + 0.5) / 3.0, Ballistics.pullProgress(5), 1e-9);
        assertEquals(3.0, Ballistics.bowSpeed(20), 1e-9, "a full draw is 3 blocks per tick");
    }

    @Test
    void aimsUpwardsAsTheTargetGetsFurther() {
        double near = Ballistics.solve(5, 0, FULL_DRAW).elevationDegrees();
        double mid = Ballistics.solve(30, 0, FULL_DRAW).elevationDegrees();
        double far = Ballistics.solve(60, 0, FULL_DRAW).elevationDegrees();
        assertTrue(near < mid && mid < far, "drop compensation grows with range: " + near + " " + mid + " " + far);
        assertTrue(near > 0, "even a 5-block shot needs some elevation");
    }

    @Test
    void flightTimeGrowsWithRange() {
        assertTrue(Ballistics.solve(10, 0, FULL_DRAW).flightTicks()
                < Ballistics.solve(40, 0, FULL_DRAW).flightTicks());
    }

    @Test
    void dragMakesTheDropWorseThanTheClosedForm() {
        // The usual "10*t²" rule ignores drag. At range the real arrow is slower, hangs longer and
        // falls further, so the honest solution has to aim higher than the drag-free one does.
        double horizontal = 50;
        Ballistics.Aim aim = Ballistics.solve(horizontal, 0, FULL_DRAW);
        assertNotNull(aim);
        double dragFree = Math.toDegrees(0.5 * Math.asin(Ballistics.GRAVITY * horizontal
                / (FULL_DRAW * FULL_DRAW)));
        assertTrue(aim.elevationDegrees() > dragFree + 0.5,
                "expected more than the drag-free " + dragFree + ", got " + aim.elevationDegrees());
    }

    @Test
    void takesTheFlatArcNotTheLobbedOne() {
        // Both a shallow and a steep angle reach a target at the same range. The flat one arrives
        // sooner and is far harder to walk out of, so that is the one to pick.
        Ballistics.Aim aim = Ballistics.solve(30, 0, FULL_DRAW);
        assertNotNull(aim);
        assertTrue(aim.elevationDegrees() < 45, "lobbed the shot: " + aim.elevationDegrees());
    }

    @Test
    void solutionsActuallyHitWhatTheyAimAt() {
        for (double horizontal : new double[]{3, 8, 15, 30, 45, 60}) {
            for (double dy : new double[]{-20, -5, 0, 5, 15}) {
                Ballistics.Aim aim = Ballistics.solve(horizontal, dy, FULL_DRAW);
                if (aim == null) continue;               // out of range is a legitimate answer
                double impact = simulateImpactHeight(horizontal, FULL_DRAW, aim.elevationDegrees());
                assertEquals(dy, impact, 0.05,
                        "aim at " + horizontal + " blocks out, " + dy + " up landed at " + impact);
            }
        }
    }

    @Test
    void refusesShotsItCannotMake() {
        assertNull(Ballistics.solve(500, 0, FULL_DRAW), "far beyond a bow's reach");
        assertNull(Ballistics.solve(40, 200, FULL_DRAW), "straight up a cliff face");
        assertNull(Ballistics.solve(0, 0, FULL_DRAW), "no horizontal distance to solve");
        assertNull(Ballistics.solve(10, 0, 0), "an undrawn bow launches nothing");
    }

    @Test
    void aimsDownwardsAtSomethingBelowAndClose() {
        Ballistics.Aim aim = Ballistics.solve(4, -20, FULL_DRAW);
        assertNotNull(aim);
        assertTrue(aim.elevationDegrees() < 0, "should be pointing down, got " + aim.elevationDegrees());
    }

    /**
     * An independent re-implementation of {@code PersistentProjectileEntity.tick()}: move by the
     * current velocity, then drag, then gravity. Deliberately not shared with the solver.
     */
    private static double simulateImpactHeight(double horizontal, double speed, double elevationDegrees) {
        double theta = Math.toRadians(elevationDegrees);
        double vx = Math.cos(theta) * speed;
        double vy = Math.sin(theta) * speed;
        double x = 0;
        double y = 0;
        for (int tick = 0; tick < 400; tick++) {
            double nextX = x + vx;
            double nextY = y + vy;
            if (nextX >= horizontal) {
                return y + (horizontal - x) / (nextX - x) * (nextY - y);
            }
            x = nextX;
            y = nextY;
            vx *= Ballistics.DRAG;
            vy = vy * Ballistics.DRAG - Ballistics.GRAVITY;
        }
        return Double.NaN;
    }
}
