package dev.mezzo.clef.render;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PngEncoderTest {

    @Test
    void producesValidPngThatRoundTrips() throws Exception {
        int[] argb = {
                0xFFFF0000, 0xFF00FF00,
                0xFF0000FF, 0xFFFFFFFF
        };
        byte[] png = PngEncoder.toPng(argb, 2, 2);

        // PNG magic signature.
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) assertEquals(sig[i], png[i], "byte " + i);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(2, img.getWidth());
        assertEquals(2, img.getHeight());
        assertEquals(0xFFFF0000, img.getRGB(0, 0));
        assertEquals(0xFFFFFFFF, img.getRGB(1, 1));
    }

    @Test
    void rejectsMismatchedDimensions() {
        assertThrows(IllegalArgumentException.class, () -> PngEncoder.toPng(new int[3], 2, 2));
    }
}
