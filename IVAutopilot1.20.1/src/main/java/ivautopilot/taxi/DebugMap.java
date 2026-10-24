package ivautopilot.taxi;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * A picture of a /genwaypoints run, for checking what it found: dark green = ground it ignored,
 * grey = taxiway surface it used, light blue-grey = runway (left out), orange = surface blocks
 * skipped for being too far above/below ground level; dark red = things standing on the ground; yellow lines + red dots = the network it
 * laid; magenta = waypoints it couldn't place (something in the way); cyan = joins between pieces.
 * Big areas are scaled down to at most 2048 pixels a side.
 */
public final class DebugMap {
    private static final int MAX_PIXELS = 2048;
    private final BufferedImage image;
    private final int scale;

    private DebugMap(int w, int h, int scale) {
        this.scale = scale;
        this.image = new BufferedImage(Math.max(1, (w + scale - 1) / scale), Math.max(1, (h + scale - 1) / scale), BufferedImage.TYPE_INT_RGB);
    }

    static DebugMap surface(int w, int h, BitSet mask, BitSet band, BitSet offLevel, BitSet obstacle) {
        int scale = Math.max(1, (Math.max(w, h) + MAX_PIXELS - 1) / MAX_PIXELS);
        DebugMap map = new DebugMap(w, h, scale);
        for (int py = 0; py < map.image.getHeight(); py++) {
            for (int px = 0; px < map.image.getWidth(); px++) {
                // A pixel covers scale x scale cells: show the most important thing in it.
                int colour = 0x1E3A1E;
                for (int dz = 0; dz < scale && colour != 0x9A9A9A; dz++) {
                    int z = py * scale + dz;
                    if (z >= h) {
                        break;
                    }
                    for (int dx = 0; dx < scale; dx++) {
                        int x = px * scale + dx;
                        if (x >= w) {
                            break;
                        }
                        int i = x + z * w;
                        if (mask.get(i)) {
                            colour = 0x9A9A9A;
                            break;
                        } else if (band.get(i)) {
                            colour = 0xB8C8DC;
                        } else if (offLevel.get(i) && colour == 0x1E3A1E) {
                            colour = 0xE07020;
                        } else if (obstacle.get(i) && colour == 0x1E3A1E) {
                            colour = 0x6A2A2A;
                        }
                    }
                }
                map.image.setRGB(px, py, colour);
            }
        }
        return map;
    }

    void line(int x1, int z1, int x2, int z2, int rgb) {
        int steps = Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1)) / scale);
        for (int s = 0; s <= steps; s++) {
            set((x1 + (x2 - x1) * s / steps) / scale, (z1 + (z2 - z1) * s / steps) / scale, rgb);
        }
    }

    void dot(int x, int z, int rgb) {
        int px = x / scale, py = z / scale;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                set(px + dx, py + dy, rgb);
            }
        }
    }

    private void set(int px, int py, int rgb) {
        if (px >= 0 && py >= 0 && px < image.getWidth() && py < image.getHeight()) {
            image.setRGB(px, py, rgb);
        }
    }

    /** Write the PNG; returns the file name shown to the player, or null if it couldn't be written. */
    @Nullable
    String save(Path file) {
        try {
            ImageIO.write(image, "png", file.toFile());
            return file.getFileName().toString();
        } catch (Exception e) {
            ivautopilot.IvAutopilot.LOGGER.warn("Couldn't save the taxi network map", e);
            return null;
        }
    }
}
