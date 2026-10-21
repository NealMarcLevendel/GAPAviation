package ivautopilot.nav;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Terrain-aware route planning (runs off the main thread).
 *
 * The area between start and goal is divided into cells. Each cell gets a minimum safe altitude
 * (highest generated terrain in and around it + clearance). A* then finds the shortest 8-connected
 * path, treating cells whose safe altitude is above the cruise altitude as very expensive - so the
 * route goes AROUND high ground when a reasonable detour exists, and over it (climbing) otherwise.
 * The path is then straightened (string-pulling) into a few waypoints, each with the altitude
 * needed to clear everything on the leg leading to it.
 */
public final class RoutePlanner {
    public static final int CELL = 48;
    private static final int MARGIN_CELLS = 24;
    private static final double OVER_TERRAIN_PENALTY = 6.0;

    /** Ground height lookup used for planning (Terrain implements this). */
    public interface HeightSource {
        int generated(int x, int z);
    }

    public record Waypoint(double x, double z, double altitude) {
        public Vec3 pos() {
            return new Vec3(x, altitude, z);
        }
    }

    private RoutePlanner() {
    }

    /**
     * @param cruise   preferred cruise altitude (absolute Y)
     * @param clearance blocks of clearance above generated terrain
     */
    public static List<Waypoint> plan(HeightSource terrain, Vec3 start, Vec3 goal, double cruise, double clearance) {
        int x0 = (int) Math.floor(Math.min(start.x, goal.x) / CELL) - MARGIN_CELLS;
        int z0 = (int) Math.floor(Math.min(start.z, goal.z) / CELL) - MARGIN_CELLS;
        int x1 = (int) Math.floor(Math.max(start.x, goal.x) / CELL) + MARGIN_CELLS;
        int z1 = (int) Math.floor(Math.max(start.z, goal.z) / CELL) + MARGIN_CELLS;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;

        // Generated terrain at each cell centre and corners, then dilate by one cell for lateral margin.
        double[] ground = new double[w * h];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int cx = (x0 + i) * CELL, cz = (z0 + j) * CELL;
                int m = Math.max(terrain.generated(cx + CELL / 2, cz + CELL / 2),
                        Math.max(terrain.generated(cx, cz), terrain.generated(cx + CELL - 1, cz + CELL - 1)));
                ground[i * h + j] = m;
            }
        }
        double[] msa = new double[w * h];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                double m = ground[i * h + j];
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a >= 0 && a < w && b >= 0 && b < h) {
                            m = Math.max(m, ground[a * h + b]);
                        }
                    }
                }
                msa[i * h + j] = m + clearance;
            }
        }

        int si = clamp((int) Math.floor(start.x / CELL) - x0, w), sj = clamp((int) Math.floor(start.z / CELL) - z0, h);
        int gi = clamp((int) Math.floor(goal.x / CELL) - x0, w), gj = clamp((int) Math.floor(goal.z / CELL) - z0, h);

        // A* (8-connected).
        double[] g = new double[w * h];
        Arrays.fill(g, Double.MAX_VALUE);
        int[] from = new int[w * h];
        Arrays.fill(from, -1);
        boolean[] closed = new boolean[w * h];
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        int start1 = si * h + sj, goal1 = gi * h + gj;
        g[start1] = 0;
        open.add(new double[]{heuristic(si, sj, gi, gj), start1});
        while (!open.isEmpty()) {
            int cur = (int) open.poll()[1];
            if (closed[cur]) {
                continue;
            }
            closed[cur] = true;
            if (cur == goal1) {
                break;
            }
            int ci = cur / h, cj = cur % h;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) {
                        continue;
                    }
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || ni >= w || nj < 0 || nj >= h) {
                        continue;
                    }
                    int n = ni * h + nj;
                    double step = (di != 0 && dj != 0) ? Math.sqrt(2) : 1;
                    double excess = Math.max(0, msa[n] - cruise);
                    double cost = step * (excess > 0 ? OVER_TERRAIN_PENALTY + excess / 20.0 : 1);
                    if (g[cur] + cost < g[n]) {
                        g[n] = g[cur] + cost;
                        from[n] = cur;
                        open.add(new double[]{g[n] + heuristic(ni, nj, gi, gj), n});
                    }
                }
            }
        }

        List<int[]> cells = new ArrayList<>();
        for (int c = goal1; c != -1; c = from[c]) {
            cells.add(new int[]{c / h, c % h});
            if (c == start1) {
                break;
            }
        }
        Collections.reverse(cells);
        if (cells.isEmpty() || cells.get(0)[0] != si || cells.get(0)[1] != sj) {
            // No path found (shouldn't happen on an open grid): fly direct at a safe altitude.
            double alt = Math.max(cruise, maxAlong(msa, h, w, x0, z0, start, goal));
            return List.of(new Waypoint(goal.x, goal.z, alt));
        }

        // String-pull: keep a waypoint only when the straight line from the last kept one would
        // cross terrain higher than the path through the cells did.
        List<Waypoint> out = new ArrayList<>();
        Vec3 anchor = start;
        double anchorLimit = Math.max(cruise, msa[si * h + sj]);
        int k = 1;
        while (k < cells.size()) {
            int best = k;
            for (int t = cells.size() - 1; t > k; t--) {
                Vec3 target = centre(cells.get(t), x0, z0);
                if (maxAlong(msa, h, w, x0, z0, anchor, target) <= Math.max(cruise, maxCells(msa, h, cells, k, t))) {
                    best = t;
                    break;
                }
            }
            Vec3 point = best == cells.size() - 1 ? goal : centre(cells.get(best), x0, z0);
            double alt = Math.max(cruise, maxAlong(msa, h, w, x0, z0, anchor, point));
            out.add(new Waypoint(point.x, point.z, alt));
            anchor = point;
            k = best + 1;
        }
        if (out.isEmpty() || out.get(out.size() - 1).x() != goal.x || out.get(out.size() - 1).z() != goal.z) {
            out.add(new Waypoint(goal.x, goal.z, Math.max(cruise, maxAlong(msa, h, w, x0, z0, anchor, goal))));
        }
        return out;
    }

    private static double heuristic(int i, int j, int gi, int gj) {
        return Math.sqrt((double) (i - gi) * (i - gi) + (double) (j - gj) * (j - gj));
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    private static Vec3 centre(int[] cell, int x0, int z0) {
        return new Vec3((x0 + cell[0]) * CELL + CELL / 2.0, 0, (z0 + cell[1]) * CELL + CELL / 2.0);
    }

    private static double maxCells(double[] msa, int h, List<int[]> cells, int from, int to) {
        double m = Double.NEGATIVE_INFINITY;
        for (int t = from; t <= to; t++) {
            m = Math.max(m, msa[cells.get(t)[0] * h + cells.get(t)[1]]);
        }
        return m;
    }

    /** Highest safe altitude of the cells a straight line crosses. */
    static double maxAlong(double[] msa, int h, int w, int x0, int z0, Vec3 a, Vec3 b) {
        double len = Math.sqrt(Math.pow(b.x - a.x, 2) + Math.pow(b.z - a.z, 2));
        int steps = Math.max(1, (int) (len / (CELL / 3.0)));
        double m = Double.NEGATIVE_INFINITY;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int i = clamp((int) Math.floor((a.x + (b.x - a.x) * t) / CELL) - x0, w);
            int j = clamp((int) Math.floor((a.z + (b.z - a.z) * t) / CELL) - z0, h);
            m = Math.max(m, msa[i * h + j]);
        }
        return m;
    }
}
