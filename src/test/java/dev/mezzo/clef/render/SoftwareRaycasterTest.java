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

    private static int center(int[] px, int w, int h) {
        return px[(h / 2) * w + (w / 2)];
    }

    private static void assertVec(double[] expected, double[] actual) {
        for (int i = 0; i < 3; i++) assertEquals(expected[i], actual[i], 1e-9);
    }
}
