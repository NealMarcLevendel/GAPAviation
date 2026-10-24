package ivautopilot.taxi;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a grid of taxiable surface (taxiways, aprons) into a taxi network: the surface's centerlines
 * (morphological skeleton), with waypoints at junctions, ends, bends and every `spacing` cells, linked
 * along the centerlines. Pure grid code (no Minecraft), so it can run off the server thread.
 *
 * Memory: the grids are bit sets (1 bit per cell: an 8192 x 8192 area is 8 MB per grid, and the work
 * needs about seven at once); everything else is per waypoint, not per cell.
 */
public final class SkeletonGraph {
    /** Waypoints (grid x, z), links (indices into nodes), and which waypoints are dead ends touching the runway band. */
    public record Result(List<int[]> nodes, List<int[]> links, List<Integer> runwayEnds) {
    }

    /** Centerline must have this much paving to each side (cells), or the stretch is dropped as too narrow. */
    static final int MIN_HALF_WIDTH = 4;
    /** ...and nothing standing (lamp posts, signs, walls, buildings) this close to it. */
    static final int OBSTACLE_CLEARANCE = 3;
    /** Waypoints closer than this along a link are merged (intersections otherwise grow little zig-zags). */
    static final double MERGE_DISTANCE = 12;

    private final int w, h;
    private final BitSet img;
    private final BitSet band;
    private final BitSet obstacle;
    /** The paved area after gap filling (before thinning), for the clearance check. */
    private BitSet paved;

    private SkeletonGraph(int w, int h, BitSet mask, BitSet band, BitSet obstacle) {
        this.w = w;
        this.h = h;
        this.img = (BitSet) mask.clone();
        this.band = band;
        this.obstacle = obstacle;
    }

    /**
     * @param mask    taxiable cells (bit x + z * w)
     * @param band    runway cells (not taxiable; dead ends next to them become runway entrances)
     * @param spacing max distance between waypoints along a taxiway, cells
     */
    public static Result build(int w, int h, BitSet mask, BitSet band, double spacing) {
        return build(w, h, mask, band, new BitSet(), spacing, p -> {
        });
    }

    /**
     * As above, with obstacles (cells with something standing on them: never bridged, kept clear of),
     * reporting rough progress 0..1 (called from the thread doing the work).
     */
    public static Result build(int w, int h, BitSet mask, BitSet band, BitSet obstacle, double spacing, java.util.function.DoubleConsumer progress) {
        return build(w, h, mask, band, obstacle, new BitSet(), spacing, progress);
    }

    /**
     * markings: flat man-made blocks at ground level that aren't the listed paving (painted lines,
     * stand markings, hatching). Those within MARKING_REACH of the paving count as paving too.
     */
    public static Result build(int w, int h, BitSet mask, BitSet band, BitSet obstacle, BitSet markings, double spacing,
                               java.util.function.DoubleConsumer progress) {
        SkeletonGraph s = new SkeletonGraph(w, h, mask, band, obstacle);
        progress.accept(0.01);
        s.absorbMarkings(markings);
        progress.accept(0.02);
        s.close();
        progress.accept(0.1);
        s.removeSmall(40);
        s.paved = (BitSet) s.img.clone();
        progress.accept(0.2);
        s.thin(progress);
        progress.accept(0.8);
        s.clearance();
        for (int i = 0; i < 3; i++) {
            s.prune(10);
        }
        progress.accept(0.9);
        Result result = merge(s.graph(spacing), MERGE_DISTANCE);
        s.paved = null;
        progress.accept(1);
        return result;
    }

    /**
     * Drop centerline cells without room around them: less than MIN_HALF_WIDTH of paving to a side
     * (a narrow service road, a gap between buildings), or an obstacle within OBSTACLE_CLEARANCE.
     * Runway cells and the edge of the area count as open.
     */
    private void clearance() {
        IntArrayList drop = new IntArrayList();
        int r = Math.max(MIN_HALF_WIDTH, OBSTACLE_CLEARANCE);
        for (int i = img.nextSetBit(0); i >= 0; i = img.nextSetBit(i + 1)) {
            int x = i % w, z = i / w;
            boolean tight = false;
            for (int dz = -r; dz <= r && !tight; dz++) {
                for (int dx = -r; dx <= r && !tight; dx++) {
                    int xx = x + dx, zz = z + dz;
                    if (xx < 0 || zz < 0 || xx >= w || zz >= h) {
                        continue;
                    }
                    int j = xx + zz * w, d2 = dx * dx + dz * dz;
                    if (d2 <= OBSTACLE_CLEARANCE * OBSTACLE_CLEARANCE && obstacle.get(j)) {
                        tight = true;
                    } else if (d2 <= MIN_HALF_WIDTH * MIN_HALF_WIDTH && !paved.get(j) && !band.get(j)) {
                        tight = true;
                    }
                }
            }
            if (tight) {
                drop.add(i);
            }
        }
        for (int k = 0; k < drop.size(); k++) {
            img.clear(drop.getInt(k));
        }
    }

    /**
     * Merge waypoints joined by a link shorter than `distance` (junction clusters, the little
     * zig-zags thinning leaves at wide intersections) into one, at the junction if one of them is
     * one, else halfway. Links are rebuilt without duplicates or loops.
     */
    static Result merge(Result in, double distance) {
        int n = in.nodes().size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        int[] degree = new int[n];
        for (int[] l : in.links()) {
            degree[l[0]]++;
            degree[l[1]]++;
        }
        double[][] pos = new double[n][];
        for (int i = 0; i < n; i++) {
            pos[i] = new double[]{in.nodes().get(i)[0], in.nodes().get(i)[1], 1, degree[i] >= 3 ? 1 : 0};
        }
        List<int[]> links = new ArrayList<>(in.links());
        links.sort((a, b) -> Double.compare(len(in, a), len(in, b)));
        for (int[] l : links) {
            int a = root(parent, l[0]), b = root(parent, l[1]);
            if (a == b) {
                continue;
            }
            double d = Math.hypot(pos[a][0] / pos[a][2] - pos[b][0] / pos[b][2], pos[a][1] / pos[a][2] - pos[b][1] / pos[b][2]);
            if (d >= distance) {
                continue;
            }
            parent[a] = b;
            // Position: junctions win; otherwise the average.
            if (pos[a][3] > pos[b][3]) {
                pos[b] = pos[a].clone();
            } else if (pos[a][3] == pos[b][3]) {
                pos[b] = new double[]{pos[a][0] + pos[b][0], pos[a][1] + pos[b][1], pos[a][2] + pos[b][2], pos[b][3]};
            }
        }
        int[] index = new int[n];
        java.util.Arrays.fill(index, -1);
        List<int[]> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int r = root(parent, i);
            if (index[r] < 0) {
                index[r] = nodes.size();
                nodes.add(new int[]{(int) Math.round(pos[r][0] / pos[r][2]), (int) Math.round(pos[r][1] / pos[r][2])});
            }
        }
        Set<Long> seen = new HashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int[] l : in.links()) {
            int a = index[root(parent, l[0])], b = index[root(parent, l[1])];
            if (a != b && seen.add(Math.min(a, b) * 10_000_000L + Math.max(a, b))) {
                out.add(new int[]{a, b});
            }
        }
        Set<Integer> ends = new HashSet<>();
        for (int e : in.runwayEnds()) {
            ends.add(index[root(parent, e)]);
        }
        // A merged node is only a runway end if it's still a dead end.
        int[] deg = new int[nodes.size()];
        for (int[] l : out) {
            deg[l[0]]++;
            deg[l[1]]++;
        }
        List<Integer> runwayEnds = new ArrayList<>();
        for (int e : ends) {
            if (deg[e] <= 1) {
                runwayEnds.add(e);
            }
        }
        return new Result(nodes, out, runwayEnds);
    }

    private static double len(Result r, int[] l) {
        int[] a = r.nodes().get(l[0]), b = r.nodes().get(l[1]);
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private boolean get(int x, int z) {
        return x >= 0 && z >= 0 && x < w && z < h && img.get(x + z * w);
    }

    private boolean isBand(int x, int z) {
        return x >= 0 && z >= 0 && x < w && z < h && band.get(x + z * w);
    }

    /** How far into marking blocks (from the listed paving) the taxiway surface extends. */
    static final int MARKING_REACH = 6;

    /** Grow the paving into adjoining markings, up to MARKING_REACH cells (breadth-first over marking cells only). */
    private void absorbMarkings(BitSet markings) {
        if (markings.isEmpty()) {
            return;
        }
        IntArrayList frontier = new IntArrayList();
        for (int i = img.nextSetBit(0); i >= 0; i = img.nextSetBit(i + 1)) {
            frontier.add(i);
        }
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int step = 0; step < MARKING_REACH && !frontier.isEmpty(); step++) {
            IntArrayList next = new IntArrayList();
            for (int k = 0; k < frontier.size(); k++) {
                int i = frontier.getInt(k), x = i % w, z = i / w;
                for (int[] d : n4) {
                    int xx = x + d[0], zz = z + d[1];
                    if (xx >= 0 && zz >= 0 && xx < w && zz < h) {
                        int j = xx + zz * w;
                        if (markings.get(j) && !img.get(j)) {
                            img.set(j);
                            next.add(j);
                        }
                    }
                }
            }
            frontier = next;
        }
    }

    /** Fill gaps up to three cells wide (painted lines, lights, a missing block): dilate, then erode. Only looks near surface cells. */
    private void close() {
        int r = 2;
        BitSet dilated = new BitSet(w * h);
        for (int i = img.nextSetBit(0); i >= 0; i = img.nextSetBit(i + 1)) {
            int x = i % w, z = i / w;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int xx = x + dx, zz = z + dz;
                    if (xx >= 0 && zz >= 0 && xx < w && zz < h && !band.get(xx + zz * w) && !obstacle.get(xx + zz * w)) {
                        dilated.set(xx + zz * w);
                    }
                }
            }
        }
        BitSet closed = (BitSet) img.clone();
        for (int i = dilated.nextSetBit(0); i >= 0; i = dilated.nextSetBit(i + 1)) {
            if (img.get(i)) {
                continue;
            }
            int x = i % w, z = i / w;
            boolean all = true;
            for (int dz = -r; dz <= r && all; dz++) {
                for (int dx = -r; dx <= r && all; dx++) {
                    int xx = x + dx, zz = z + dz;
                    // Outside the area counts as surface so taxiways running off the edge aren't eaten.
                    all = xx < 0 || zz < 0 || xx >= w || zz >= h || dilated.get(xx + zz * w) || band.get(xx + zz * w);
                }
            }
            if (all && !obstacle.get(i)) {
                closed.set(i); // a gap in the paving (a marking, a missing block) - never something standing there
            }
        }
        img.clear();
        img.or(closed);
    }

    /** Drop specks: connected patches smaller than minArea cells (counted, then cleared in a second pass). */
    private void removeSmall(int minArea) {
        BitSet seen = new BitSet(w * h);
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int start = img.nextSetBit(0); start >= 0; start = img.nextSetBit(start + 1)) {
            if (seen.get(start)) {
                continue;
            }
            int count = 0;
            queue.enqueue(start);
            seen.set(start);
            while (!queue.isEmpty()) {
                int i = queue.dequeueInt();
                count++;
                int x = i % w, z = i / w;
                for (int[] d : n4) {
                    int xx = x + d[0], zz = z + d[1];
                    if (get(xx, zz) && !seen.get(xx + zz * w)) {
                        seen.set(xx + zz * w);
                        queue.enqueue(xx + zz * w);
                    }
                }
            }
            if (count < minArea) {
                queue.enqueue(start);
                img.clear(start);
                while (!queue.isEmpty()) {
                    int i = queue.dequeueInt();
                    int x = i % w, z = i / w;
                    for (int[] d : n4) {
                        int xx = x + d[0], zz = z + d[1];
                        if (get(xx, zz)) {
                            img.clear(xx + zz * w);
                            queue.enqueue(xx + zz * w);
                        }
                    }
                }
            }
        }
    }

    /**
     * Zhang-Suen thinning to one-cell-wide centerlines. Walks the set bits directly each round (no
     * per-cell list), so memory stays at the bit grids however much surface there is.
     */
    private void thin(java.util.function.DoubleConsumer progress) {
        IntArrayList remove = new IntArrayList();
        double initial = Math.max(1, img.cardinality());
        boolean changed = true;
        while (changed) {
            changed = false;
            // Progress: how much of the surface has been peeled away so far (0.2 .. 0.85).
            progress.accept(0.2 + 0.65 * Math.min(1, 1 - img.cardinality() / initial));
            for (int pass = 0; pass < 2; pass++) {
                remove.clear();
                for (int i = img.nextSetBit(0); i >= 0; i = img.nextSetBit(i + 1)) {
                    int x = i % w, z = i / w;
                    boolean p2 = get(x, z - 1), p3 = get(x + 1, z - 1), p4 = get(x + 1, z), p5 = get(x + 1, z + 1);
                    boolean p6 = get(x, z + 1), p7 = get(x - 1, z + 1), p8 = get(x - 1, z), p9 = get(x - 1, z - 1);
                    if (p2 && p4 && p6 && p8) {
                        continue; // interior cell: can't be removed this pass (fast path for big aprons)
                    }
                    boolean[] ring = {p2, p3, p4, p5, p6, p7, p8, p9, p2};
                    int b = 0, a = 0;
                    for (int r = 0; r < 8; r++) {
                        if (ring[r]) {
                            b++;
                        }
                        if (!ring[r] && ring[r + 1]) {
                            a++;
                        }
                    }
                    if (b < 2 || b > 6 || a != 1) {
                        continue;
                    }
                    if (pass == 0 ? !(p2 && p4 && p6) && !(p4 && p6 && p8) : !(p2 && p4 && p8) && !(p2 && p6 && p8)) {
                        remove.add(i);
                    }
                }
                for (int k = 0; k < remove.size(); k++) {
                    img.clear(remove.getInt(k));
                }
                changed |= !remove.isEmpty();
            }
        }
    }

    private int degree(int x, int z) {
        int n = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && get(x + dx, z + dz)) {
                    n++;
                }
            }
        }
        return n;
    }

    private boolean nearBand(int x, int z, int r) {
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (isBand(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean onEdge(int x, int z) {
        return x <= 1 || z <= 1 || x >= w - 2 || z >= h - 2;
    }

    /** Remove short side branches (ragged edges), except ones reaching the runway or the edge of the area. */
    private void prune(int maxLength) {
        IntArrayList ends = new IntArrayList();
        for (int i = img.nextSetBit(0); i >= 0; i = img.nextSetBit(i + 1)) {
            int x = i % w, z = i / w;
            if (degree(x, z) == 1 && !nearBand(x, z, 20) && !onEdge(x, z)) {
                ends.add(i);
            }
        }
        for (int e = 0; e < ends.size(); e++) {
            int start = ends.getInt(e);
            if (!img.get(start)) {
                continue;
            }
            IntArrayList branch = new IntArrayList();
            int cx = start % w, cz = start / w, px = -1, pz = -1;
            boolean reachedJunction = false;
            while (branch.size() <= maxLength) {
                branch.add(cx + cz * w);
                int nx = -1, nz = -1, count = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = cx + dx, zz = cz + dz;
                        if ((dx != 0 || dz != 0) && get(xx, zz) && !(xx == px && zz == pz) && !branch.contains(xx + zz * w)) {
                            count++;
                            nx = xx;
                            nz = zz;
                        }
                    }
                }
                if (count == 0) {
                    break; // isolated line: keep
                }
                if (count > 1 || degree(nx, nz) > 2) {
                    reachedJunction = true;
                    break;
                }
                px = cx;
                pz = cz;
                cx = nx;
                cz = nz;
            }
            if (reachedJunction && branch.size() <= maxLength) {
                for (int k = 0; k < branch.size(); k++) {
                    img.clear(branch.getInt(k));
                }
            }
        }
    }

    /** Key cells (ends, junctions) become nodes; trace the lines between them; simplify; subdivide. */
    private Result graph(double spacing) {
        Int2IntOpenHashMap nodeOf = new Int2IntOpenHashMap();
        nodeOf.defaultReturnValue(-1);
        List<int[]> nodes = new ArrayList<>();
        List<int[]> links = new ArrayList<>();
        // Junction clusters (adjacent junction cells) share one node.
        for (int start = img.nextSetBit(0); start >= 0; start = img.nextSetBit(start + 1)) {
            int x = start % w, z = start / w;
            if (nodeOf.get(start) >= 0 || degree(x, z) == 2) {
                continue;
            }
            int id = nodes.size();
            boolean junction = degree(x, z) > 2;
            IntArrayList cluster = new IntArrayList();
            IntArrayFIFOQueue q = new IntArrayFIFOQueue();
            q.enqueue(start);
            nodeOf.put(start, id);
            while (!q.isEmpty()) {
                int i = q.dequeueInt();
                cluster.add(i);
                if (!junction) {
                    continue;
                }
                int cx = i % w, cz = i / w;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = cx + dx, zz = cz + dz;
                        if (get(xx, zz) && nodeOf.get(xx + zz * w) < 0 && degree(xx, zz) > 2) {
                            nodeOf.put(xx + zz * w, id);
                            q.enqueue(xx + zz * w);
                        }
                    }
                }
            }
            double sx = 0, sz = 0;
            for (int k = 0; k < cluster.size(); k++) {
                sx += cluster.getInt(k) % w;
                sz += (double) (cluster.getInt(k) / w);
            }
            nodes.add(new int[]{(int) Math.round(sx / cluster.size()), (int) Math.round(sz / cluster.size())});
        }
        // Trace each line from a node cell through degree-2 cells to the next node.
        BitSet used = new BitSet();
        Set<Long> linked = new HashSet<>();
        int[] keyCells = nodeOf.keySet().toIntArray();
        java.util.Arrays.sort(keyCells);
        for (int start : keyCells) {
            int startNode = nodeOf.get(start);
            int sx = start % w, sz = start / w;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = sx + dx, zz = sz + dz;
                    if ((dx == 0 && dz == 0) || !get(xx, zz)) {
                        continue;
                    }
                    int first = xx + zz * w;
                    if (nodeOf.get(first) == startNode || (nodeOf.get(first) < 0 && used.get(first))) {
                        continue;
                    }
                    List<int[]> path = new ArrayList<>();
                    path.add(nodes.get(startNode));
                    int prev = start, cur = first, end = -1;
                    while (true) {
                        if (nodeOf.get(cur) >= 0) {
                            end = nodeOf.get(cur);
                            break;
                        }
                        used.set(cur);
                        path.add(new int[]{cur % w, cur / w});
                        int next = step(cur, prev, nodeOf, used, startNode, path.size());
                        if (next < 0) {
                            break;
                        }
                        prev = cur;
                        cur = next;
                    }
                    if (end >= 0) {
                        path.add(nodes.get(end));
                        addPath(nodes, links, linked, startNode, end, path, spacing);
                    }
                }
            }
        }
        List<Integer> runwayEnds = new ArrayList<>();
        int[] deg = new int[nodes.size()];
        for (int[] l : links) {
            deg[l[0]]++;
            deg[l[1]]++;
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (deg[i] <= 1 && nearBand(nodes.get(i)[0], nodes.get(i)[1], 20)) {
                runwayEnds.add(i);
            }
        }
        return new Result(nodes, links, runwayEnds);
    }

    /** Next cell along a line: onto a (different) node first, then an unused line cell, then back to the start node (a loop). */
    private int step(int cur, int prev, Int2IntOpenHashMap nodeOf, BitSet used, int startNode, int pathLength) {
        int cx = cur % w, cz = cur / w;
        for (int pass = 0; pass < 3; pass++) {
            for (int ez = -1; ez <= 1; ez++) {
                for (int ex = -1; ex <= 1; ex++) {
                    int nx = cx + ex, nz = cz + ez, ni = nx + nz * w;
                    if ((ex == 0 && ez == 0) || !get(nx, nz) || ni == prev) {
                        continue;
                    }
                    int node = nodeOf.get(ni);
                    if (pass == 0 && node >= 0 && node != startNode
                            || pass == 1 && node < 0 && !used.get(ni)
                            || pass == 2 && node == startNode && pathLength > 3) {
                        return ni;
                    }
                }
            }
        }
        return -1;
    }

    private void addPath(List<int[]> nodes, List<int[]> links, Set<Long> linked, int a, int b, List<int[]> path, double spacing) {
        if (a == b && path.size() < 6) {
            return;
        }
        List<int[]> simple = simplifyClear(path, 3);
        List<int[]> pts = new ArrayList<>();
        pts.add(simple.get(0));
        for (int i = 1; i < simple.size(); i++) {
            int[] p = simple.get(i - 1), q = simple.get(i);
            double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
            int parts = (int) Math.ceil(len / spacing);
            for (int k = 1; k < parts; k++) {
                pts.add(new int[]{(int) Math.round(p[0] + (q[0] - p[0]) * k / (double) parts), (int) Math.round(p[1] + (q[1] - p[1]) * k / (double) parts)});
            }
            pts.add(q);
        }
        int prev = a;
        for (int i = 1; i < pts.size(); i++) {
            int id;
            if (i == pts.size() - 1) {
                id = b;
            } else {
                id = nodes.size();
                nodes.add(pts.get(i));
            }
            long key = Math.min(prev, id) * 10_000_000L + Math.max(prev, id);
            if (prev != id && linked.add(key)) {
                links.add(new int[]{prev, id});
            }
            prev = id;
        }
    }

    /**
     * Douglas-Peucker that only replaces a stretch with a straight link if the straight line stays
     * on clear paving (so a link never cuts a corner through a building or past a post).
     */
    private List<int[]> simplifyClear(List<int[]> pts, double tolerance) {
        if (pts.size() < 3) {
            return new ArrayList<>(pts);
        }
        int[] a = pts.get(0), b = pts.get(pts.size() - 1);
        double best = -1;
        int index = -1;
        for (int i = 1; i < pts.size() - 1; i++) {
            double d = distanceToSegment(pts.get(i), a, b);
            if (d > best) {
                best = d;
                index = i;
            }
        }
        if (best <= tolerance && chordClear(a, b)) {
            List<int[]> out = new ArrayList<>();
            out.add(a);
            out.add(b);
            return out;
        }
        if (index < 0) {
            index = pts.size() / 2;
        }
        List<int[]> left = simplifyClear(pts.subList(0, index + 1), tolerance);
        List<int[]> right = simplifyClear(pts.subList(index, pts.size()), tolerance);
        left.remove(left.size() - 1);
        left.addAll(right);
        return left;
    }

    /** Every cell along a-b is paving (or runway) with nothing standing next to it. */
    private boolean chordClear(int[] a, int[] b) {
        if (paved == null) {
            return true;
        }
        int steps = (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]));
        for (int k = 1; k < steps; k++) {
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * k / (double) steps), z = (int) Math.round(a[1] + (b[1] - a[1]) * k / (double) steps);
            if (x < 0 || z < 0 || x >= w || z >= h) {
                continue;
            }
            if (!paved.get(x + z * w) && !band.get(x + z * w)) {
                return false;
            }
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, zz = z + dz;
                    if (xx >= 0 && zz >= 0 && xx < w && zz < h && obstacle.get(xx + zz * w)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Douglas-Peucker. */
    static List<int[]> simplify(List<int[]> pts, double tolerance) {
        if (pts.size() < 3) {
            return new ArrayList<>(pts);
        }
        int[] a = pts.get(0), b = pts.get(pts.size() - 1);
        double best = -1;
        int index = -1;
        for (int i = 1; i < pts.size() - 1; i++) {
            double d = distanceToSegment(pts.get(i), a, b);
            if (d > best) {
                best = d;
                index = i;
            }
        }
        if (best <= tolerance) {
            List<int[]> out = new ArrayList<>();
            out.add(a);
            out.add(b);
            return out;
        }
        List<int[]> left = simplify(pts.subList(0, index + 1), tolerance);
        List<int[]> right = simplify(pts.subList(index, pts.size()), tolerance);
        left.remove(left.size() - 1);
        left.addAll(right);
        return left;
    }

    private static double distanceToSegment(int[] p, int[] a, int[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / len2));
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dz);
    }
}
