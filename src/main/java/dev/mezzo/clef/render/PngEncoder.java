package dev.mezzo.clef.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Encodes an ARGB pixel array to PNG bytes using {@link ImageIO}. Pure Java, works headless
 * (no display/GPU needed) — we force {@code java.awt.headless=true} so it's safe on servers.
 */
public final class PngEncoder {

    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    public static byte[] toPng(int[] argb, int width, int height) throws IOException {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("pixel array length " + argb.length
                    + " != " + width + "x" + height);
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, width * height / 4));
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("no PNG ImageIO writer available");
        }
        return out.toByteArray();
    }

    private PngEncoder() {}
}
