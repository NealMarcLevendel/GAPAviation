package ivautopilot.nav;

import net.minecraft.world.phys.Vec3;

/**
 * A flyable path from the aircraft's position and heading to a target position and heading
 * (the approach entry, lined up with the runway): turn, straight, turn ("Dubins" CSC path) at a
 * fixed turn radius, choosing the shortest of the four left/right combinations. This is the
 * "turn outward, then back to line up" circuit, flown with a firm, constant bank.
 *
 * Plane coordinates are Minecraft x/z. Headings are Minecraft yaw in degrees (increasing = turning
 * right). Internally theta = yaw + 90 degrees so the direction is (cos theta, sin theta), and a
 * turn with sign +1 (theta increasing) is a RIGHT turn.
 *
 * The path is sampled every STEP blocks and extended straight past the target, so a follower can
 * always look ahead.
 */
public final class Pattern {
    public static final double STEP = 5, EXTENSION = 500;

    /** Sampled points (x, z). */
    public final double[] xs, zs;
    /** Index of the sample at the target (end of the second turn). */
    public final int targetIndex;
    public final double radius;
    /** Altitude at the start (the follower descends linearly from it to the target altitude). */
    public final double startAltitude;
    public int nearest;

    private Pattern(double[] xs, double[] zs, int targetIndex, double radius, double startAltitude) {
        this.xs = xs;
        this.zs = zs;
        this.targetIndex = targetIndex;
        this.radius = radius;
        this.startAltitude = startAltitude;
    }

    public double length() {
        return targetIndex * STEP;
    }

    /** Shortest turn-straight-turn path. */
    public static Pattern plan(double x0, double z0, double yaw0, double x1, double z1, double yaw1, double radius, double startAltitude) {
        double t0 = Math.toRadians(yaw0 + 90), t1 = Math.toRadians(yaw1 + 90);
        double best = Double.MAX_VALUE;
        double[] bestSeg = null;
        for (int s1 = -1; s1 <= 1; s1 += 2) {
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                double[] seg = csc(x0, z0, t0, s1, x1, z1, t1, s2, radius);
                if (seg != null && seg[0] < best) {
                    best = seg[0];
                    bestSeg = seg;
                }
            }
        }
        if (bestSeg == null) { // only if start and target circles coincide: fly straight at it
            return straight(x0, z0, x1, z1, t1, radius, startAltitude);
        }
        return sample(x0, z0, t0, bestSeg, radius, startAltitude);
    }

    /**
     * One CSC combination. Returns {total length, s1, arc1 angle, straight length, theta of straight, s2, arc2 angle}
     * or null if impossible.
     */
    private static double[] csc(double x0, double z0, double t0, int s1, double x1, double z1, double t1, int s2, double r) {
        // Turn centres: c = p + s * r * (-sin t, cos t)
        double c1x = x0 - s1 * r * Math.sin(t0), c1z = z0 + s1 * r * Math.cos(t0);
        double c2x = x1 - s2 * r * Math.sin(t1), c2z = z1 + s2 * r * Math.cos(t1);
        double dx = c2x - c1x, dz = c2z - c1z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double thetaD = Math.atan2(dz, dx);
        double straight, tu;
        if (s1 == s2) {
            if (d < 1.0E-6) {
                return null;
            }
            straight = d;
            tu = thetaD;
        } else {
            if (d < 2 * r) {
                return null;
            }
            straight = Math.sqrt(d * d - 4 * r * r);
            tu = thetaD + Math.atan2(2 * s1 * r, straight);
        }
        double a1 = mod2pi(s1 * (tu - t0));
        double a2 = mod2pi(s2 * (t1 - tu));
        return new double[]{r * a1 + straight + r * a2, s1, a1, straight, tu, s2, a2};
    }

    private static Pattern sample(double x0, double z0, double t0, double[] seg, double r, double startAltitude) {
        int s1 = (int) seg[1], s2 = (int) seg[5];
        double a1 = seg[2], straight = seg[3], tu = seg[4], a2 = seg[6];
        double total = seg[0];
        int n = (int) Math.ceil((total + EXTENSION) / STEP) + 1;
        double[] xs = new double[n], zs = new double[n];
        double c1x = x0 - s1 * r * Math.sin(t0), c1z = z0 + s1 * r * Math.cos(t0);
        // End of the first arc / start of the straight.
        double ax = c1x + s1 * r * Math.sin(tu), az = c1z - s1 * r * Math.cos(tu);
        double bx = ax + straight * Math.cos(tu), bz = az + straight * Math.sin(tu);
        double c2x = bx - s2 * r * Math.sin(tu), c2z = bz + s2 * r * Math.cos(tu);
        double t1 = tu + s2 * a2;
        double ex = c2x + s2 * r * Math.sin(t1), ez = c2z - s2 * r * Math.cos(t1);
        for (int i = 0; i < n; i++) {
            double s = i * STEP;
            if (s < r * a1) {
                double th = t0 + s1 * s / r;
                xs[i] = c1x + s1 * r * Math.sin(th);
                zs[i] = c1z - s1 * r * Math.cos(th);
            } else if (s < r * a1 + straight) {
                double l = s - r * a1;
                xs[i] = ax + l * Math.cos(tu);
                zs[i] = az + l * Math.sin(tu);
            } else if (s < total) {
                double th = tu + s2 * (s - r * a1 - straight) / r;
                xs[i] = c2x + s2 * r * Math.sin(th);
                zs[i] = c2z - s2 * r * Math.cos(th);
            } else {
                double l = s - total;
                xs[i] = ex + l * Math.cos(t1);
                zs[i] = ez + l * Math.sin(t1);
            }
        }
        return new Pattern(xs, zs, (int) Math.round(total / STEP), r, startAltitude);
    }

    private static Pattern straight(double x0, double z0, double x1, double z1, double t1, double r, double startAltitude) {
        double dx = x1 - x0, dz = z1 - z0;
        double d = Math.sqrt(dx * dx + dz * dz);
        int target = (int) Math.ceil(d / STEP);
        int n = target + (int) Math.ceil(EXTENSION / STEP) + 1;
        double[] xs = new double[n], zs = new double[n];
        for (int i = 0; i < n; i++) {
            if (i <= target) {
                double f = target == 0 ? 1 : (double) i / target;
                xs[i] = x0 + dx * f;
                zs[i] = z0 + dz * f;
            } else {
                double l = (i - target) * STEP;
                xs[i] = x1 + l * Math.cos(t1);
                zs[i] = z1 + l * Math.sin(t1);
            }
        }
        return new Pattern(xs, zs, target, r, startAltitude);
    }

    private static double mod2pi(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /** Advances and returns the index of the nearest sample (searching forward from the last one). */
    public int updateNearest(Vec3 pos) {
        int best = nearest;
        double bestD = Double.MAX_VALUE;
        int end = Math.min(xs.length - 1, nearest + 80);
        for (int i = nearest; i <= end; i++) {
            double dx = xs[i] - pos.x, dz = zs[i] - pos.z;
            double dd = dx * dx + dz * dz;
            if (dd < bestD) {
                bestD = dd;
                best = i;
            }
        }
        nearest = best;
        return best;
    }

    public double distanceTo(int index, Vec3 pos) {
        double dx = xs[index] - pos.x, dz = zs[index] - pos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
