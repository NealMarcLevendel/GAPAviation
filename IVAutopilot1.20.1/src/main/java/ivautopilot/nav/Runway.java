package ivautopilot.nav;

import net.minecraft.world.phys.Vec3;

/** A runway: centerline between two ends (block centres, top surface). */
public record Runway(String name, Vec3 a, Vec3 b) {
    public double length() {
        return Math.sqrt(Math.pow(b.x - a.x, 2) + Math.pow(b.z - a.z, 2));
    }

    /** Horizontal unit vector pointing from one end toward the other. */
    public static Vec3 heading(Vec3 from, Vec3 to) {
        return new Vec3(to.x - from.x, 0, to.z - from.z).normalize();
    }
}
