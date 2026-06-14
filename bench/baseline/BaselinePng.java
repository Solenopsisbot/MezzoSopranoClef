package bench.baseline;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Verbatim pre-optimization copy of PngEncoder (ImageIO-based) — the baseline for benchmarking. */
public final class BaselinePng {

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

    private BaselinePng() {}
}
