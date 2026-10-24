package ivautopilot.nav;

import net.minecraft.core.BlockPos;

/**
 * An airport: the rectangle between two Airport Markers with the same name (opposite corners), from
 * the ground up to the configured ceiling above the higher marker. It can contain runways, taxiways
 * and gates. Inside it, a plane departing from it keeps its wings level (rudder turns) until it is
 * out of the rectangle or above the ceiling; arriving and landing planes are never restricted.
 */
public record Airport(String name, BlockPos a, BlockPos b) {
    public int minX() {
        return Math.min(a.getX(), b.getX());
    }

    public int maxX() {
        return Math.max(a.getX(), b.getX());
    }

    public int minZ() {
        return Math.min(a.getZ(), b.getZ());
    }

    public int maxZ() {
        return Math.max(a.getZ(), b.getZ());
    }

    public int sizeX() {
        return maxX() - minX() + 1;
    }

    public int sizeZ() {
        return maxZ() - minZ() + 1;
    }

    /** Inside the rectangle (horizontally). */
    public boolean containsXZ(double x, double z) {
        return x >= minX() && x < maxX() + 1 && z >= minZ() && z < maxZ() + 1;
    }

    public boolean contains(double x, double y, double z, double ceiling) {
        return containsXZ(x, z) && y <= Math.max(a.getY(), b.getY()) + ceiling;
    }
}
