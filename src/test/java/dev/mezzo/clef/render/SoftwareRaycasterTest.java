package dev.mezzo.clef.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareRaycasterTest {

    private static final int SKY = 0xFF87CEEB;

    @Test
    void rotationVectorMatchesMinecraftConvention() {
        assertVec(new double[]{0, 0, 1}, SoftwareRaycaster.rotationVector(0, 0));    // south +Z
        assertVec(new double[]{-1, 0, 0}, SoftwareRaycaster.rotationVector(90, 0));  // west -X
        assertVec(new double[]{0, -1, 0}, SoftwareRaycaster.rotationVector(0, 90));  // straight down
    }

    @Test
    void outputHasRequestedDimensions() {
        VoxelView empty = (x, y, z) -> 0;
        RenderCamera cam = new RenderCamera(0, 0, 0, 0, 0, 70, 7, 5, 32);
        int[] px = SoftwareRaycaster.render(empty, cam, SKY, SKY);
        assertEquals(7 * 5, px.length);
    }

    @Test
    void emptyWorldRendersOnlySky() {
        VoxelView empty = (x, y, z) -> 0;
        RenderCamera cam = new RenderCamera(0.5, 0.5, 0.5, 45, 0, 70, 16, 16, 32);
        for (int p : SoftwareRaycaster.render(empty, cam, SKY, SKY)) {
            assertEquals(0xFF, p >>> 24, "every pixel opaque");
            assertEquals(SKY, p, "uniform sky color when nothing is hit");
        }
    }

    @Test
    void looksDownAtGroundSeesGround_upSeesSky() {
        // Solid green floor at y <= 0, air above.
        int green = 0xFF3CB043;
        VoxelView ground = (x, y, z) -> y <= 0 ? green : 0;

        RenderCamera down = new RenderCamera(0.5, 10, 0.5, 0, 90, 70, 17, 17, 64);
        int center = center(SoftwareRaycaster.render(ground, down, SKY, SKY), 17, 17);
        int r = (center >> 16) & 0xFF, g = (center >> 8) & 0xFF, b = center & 0xFF;
        assertTrue(g > r && g > b, "looking down should see green ground, got #" + Integer.toHexString(center));

        RenderCamera up = new RenderCamera(0.5, 10, 0.5, 0, -90, 70, 17, 17, 64);
        int sky = center(SoftwareRaycaster.render(ground, up, SKY, SKY), 17, 17);
        assertTrue((sky & 0xFF) > ((sky >> 16) & 0xFF), "looking up should see blueish sky");
    }

    @Test
    void rendersEntityBoxInFront_notBehind() {
        VoxelView empty = (x, y, z) -> 0;
        int red = 0xFFE03030;
        EntityBox box = new EntityBox(0.0, 0.0, 3.0, 1.0, 2.0, 4.0, red); // in front (+Z)

        RenderCamera facing = new RenderCamera(0.5, 0.5, 0.5, 0, 0, 70, 17, 17, 64);
        int hit = center(SoftwareRaycaster.render(empty, facing, List.of(box), SKY, SKY), 17, 17);
        int r = (hit >> 16) & 0xFF, g = (hit >> 8) & 0xFF, b = hit & 0xFF;
        assertTrue(r > g && r > b, "entity in front should color the center pixel red, got #" + Integer.toHexString(hit));

        RenderCamera away = new RenderCamera(0.5, 0.5, 0.5, 180, 0, 70, 17, 17, 64);
        int sky = center(SoftwareRaycaster.render(empty, away, List.of(box), SKY, SKY), 17, 17);
        assertEquals(SKY, sky, "entity behind the camera must not be drawn");
    }

    @Test
    void nearerBlockOccludesEntityBehindIt() {
        // Green block at the voxel directly ahead; red entity further along the same ray.
        int green = 0xFF3CB043;
        VoxelView wall = (x, y, z) -> (x == 0 && y == 0 && z == 2) ? green : 0;
        EntityBox behind = new EntityBox(0.0, 0.0, 4.0, 1.0, 1.0, 5.0, 0xFFE03030);

        RenderCamera cam = new RenderCamera(0.5, 0.5, 0.5, 0, 0, 70, 17, 17, 64);
        int hit = center(SoftwareRaycaster.render(wall, cam, List.of(behind), SKY, SKY), 17, 17);
        int r = (hit >> 16) & 0xFF, g = (hit >> 8) & 0xFF, b = hit & 0xFF;
        assertTrue(g > r && g > b, "nearer block should occlude the entity, got #" + Integer.toHexString(hit));
    }

    // ---- orthographic (topdown map) mode --------------------------------------------

    /**
     * The point of the ortho camera: parallel rays. A single 1×1 column of blocks far from the
     * image centre must still be hit, and hit at the pixel directly above it — under perspective it
     * would be squashed towards the vanishing point and missed entirely at this distance.
     */
    @Test
    void orthographicRaysAreParallelSoOffCentreColumnsStillRender() {
        int green = 0xFF3CB043;
        VoxelView pillar = (x, y, z) -> (x == 12 && z == 0 && y <= 64) ? green : 0;
        // 32 blocks tall viewport, square image, camera 40 above the pillar top and centred on x=0.
        RenderCamera cam = new RenderCamera(0.5, 104, 0.5, 180f, 90f, 70f, 64, 64, 128, 32);
        int[] px = SoftwareRaycaster.render(pillar, cam, SKY, SKY);

        // East is +X and to the right: 12 blocks east on a 32-block-wide viewport is 12/32 of the
        // half-width past centre, i.e. 3/8 of the way from the middle to the right edge.
        int column = (int) Math.round(32 + 12.0 / 32.0 * 64);
        int hit = px[32 * 64 + Math.min(63, column)];
        int r = (hit >> 16) & 0xFF, g = (hit >> 8) & 0xFF, b = hit & 0xFF;
        assertTrue(g > r && g > b,
                "off-centre pillar should render at its own column, got #" + Integer.toHexString(hit));
        assertEquals(SKY, px[32 * 64 + 32], "nothing directly under the camera, so sky");
    }

    @Test
    void orthographicViewportCoversExactlyOrthoHeightBlocks() {
        int green = 0xFF3CB043;
        // A flat floor spanning z in [-15, 15] only: the 32-block viewport should see its edges.
        VoxelView strip = (x, y, z) -> (y <= 64 && z >= -15 && z <= 15) ? green : 0;
        RenderCamera cam = new RenderCamera(0.5, 104, 0.5, 180f, 90f, 70f, 64, 64, 128, 32);
        int[] px = SoftwareRaycaster.render(strip, cam, SKY, SKY);
        assertNotEquals(SKY, px[32 * 64 + 32], "the middle of the strip is floor");
        // North is up: the top row looks at z ≈ 0.5 - 16, which is outside the strip.
        assertEquals(SKY, px[32], "the top row falls off the end of the strip");
    }

    private static int center(int[] px, int w, int h) {
        return px[(h / 2) * w + (w / 2)];
    }

    private static void assertVec(double[] expected, double[] actual) {
        for (int i = 0; i < 3; i++) assertEquals(expected[i], actual[i], 1e-9);
    }
}
