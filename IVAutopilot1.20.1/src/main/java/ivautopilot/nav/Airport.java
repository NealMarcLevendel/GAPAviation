package ivautopilot.nav;

import net.minecraft.core.BlockPos;

/** A no-bank zone: a cylinder of this radius around the marker, from the ground up to the configured ceiling. */
public record Airport(String name, BlockPos pos, int radius) {
    public boolean contains(double x, double y, double z, double ceiling) {
        double dx = x - (pos.getX() + 0.5), dz = z - (pos.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) radius * radius && y <= pos.getY() + ceiling;
    }
}
