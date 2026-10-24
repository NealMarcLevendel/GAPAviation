package ivautopilot.taxi;

import ivautopilot.nav.Runway;
import ivautopilot.nav.RunwayRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Routes through the taxiway network (A* over waypoints, straight lines between linked ones). */
public final class TaxiPlanner {
    /** How far from the network an aircraft may be and still join it at the nearest waypoint. */
    public static final double JOIN_DISTANCE = 80;

    /**
     * A taxi route: points to follow (x/z matter), and for a runway route the runway, its departure
     * end and direction (the last two points are the threshold and a line-up point down the runway).
     */
    public record Route(List<Vec3> points, String target, @Nullable Runway runway, @Nullable Vec3 runwayStart, @Nullable Vec3 runwayEnd,
                        List<BlockPos> nodes, int holdShortIndex) {
        /**
         * The network waypoint at route point i, or null (point 0 is the aircraft's start; points after
         * holdShortIndex are on the runway). nodes.get(k) is at point k + 1.
         */
        @Nullable
        public BlockPos nodeAt(int i) {
            return i >= 1 && i - 1 < nodes.size() ? nodes.get(i - 1) : null;
        }

        public double length() {
            double d = 0;
            for (int i = 1; i < points.size(); i++) {
                d += points.get(i).distanceTo(points.get(i - 1));
            }
            return d;
        }
    }

    private TaxiPlanner() {
    }

    static Vec3 top(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5);
    }

    /** Route from where the aircraft is to a gate (named waypoint). Null if unknown or unreachable. */
    @Nullable
    public static Route toGate(ServerLevel level, Vec3 from, String gate) {
        return toGate(level, from, gate, Set.of());
    }

    /** As above, avoiding the given waypoints (e.g. ones other aircraft are holding). */
    @Nullable
    public static Route toGate(ServerLevel level, Vec3 from, String gate, Set<BlockPos> avoid) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        BlockPos goal = g.gate(gate);
        if (goal == null) {
            return null;
        }
        Path path = path(level, g, from, Set.of(goal), avoid);
        return path == null ? null : new Route(path.points, g.nodes.get(goal), null, null, null, path.nodes, -1);
    }

    /** A way off the runway after landing: the route (from the aircraft), and how far down the runway the exit is. */
    public record Exit(Route route, double along) {
    }

    /**
     * Gates to taxi to after landing: the named gate, or (gate null) every free gate, or every gate if
     * all are occupied. Empty if none.
     */
    public static Set<BlockPos> gateGoals(ServerLevel level, @Nullable String gate, java.util.function.Predicate<BlockPos> occupied) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        if (gate != null) {
            BlockPos goal = g.gate(gate);
            return goal == null ? Set.of() : Set.of(goal);
        }
        Set<BlockPos> free = new HashSet<>(), all = new HashSet<>();
        g.nodes.forEach((pos, name) -> {
            if (!name.isEmpty()) {
                all.add(pos);
                if (!occupied.test(pos)) {
                    free.add(pos);
                }
            }
        });
        return free.isEmpty() ? all : free;
    }

    /**
     * The runway exit to take after landing: a waypoint linked to this runway (or within 40 blocks of
     * its centerline) with a taxi route to one of the goals. Prefers the first one still ahead of the
     * aircraft (at least minAhead blocks down the runway), otherwise the nearest one behind. The route
     * runs along the centerline to abeam the exit, onto it, then through the network to the gate.
     */
    @Nullable
    public static Exit exitRoute(ServerLevel level, Runway runway, Vec3 threshold, Vec3 farEnd, Vec3 pos, Set<BlockPos> goals, double minAhead) {
        if (goals.isEmpty()) {
            return null;
        }
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        RunwayRegistry registry = RunwayRegistry.get(level);
        Vec3 dir = Runway.heading(threshold, farEnd);
        double here = (pos.x - threshold.x) * dir.x + (pos.z - threshold.z) * dir.z;
        Set<BlockPos> candidates = new HashSet<>();
        g.runwayLinks.forEach((node, markers) -> {
            for (BlockPos marker : markers) {
                Runway r = registry.runwayWithMarker(level.dimension(), marker);
                if (r != null && r.name().equalsIgnoreCase(runway.name())) {
                    candidates.add(node);
                }
            }
        });
        for (BlockPos node : g.nodes.keySet()) {
            double along = (node.getX() + 0.5 - threshold.x) * dir.x + (node.getZ() + 0.5 - threshold.z) * dir.z;
            double cross = Math.abs((node.getX() + 0.5 - threshold.x) * dir.z - (node.getZ() + 0.5 - threshold.z) * dir.x);
            if (along > -40 && along < runway.length() + 40 && cross < 40 && Math.abs(node.getY() - threshold.y) < 8) {
                candidates.add(node);
            }
        }
        BlockPos bestAhead = null, bestBehind = null;
        double aheadAlong = Double.MAX_VALUE, behindAlong = -Double.MAX_VALUE;
        Map<BlockPos, List<BlockPos>> paths = new HashMap<>();
        for (BlockPos node : candidates) {
            List<BlockPos> path = astar(g, node, goals, Set.of());
            if (path == null) {
                continue;
            }
            paths.put(node, path);
            double along = (node.getX() + 0.5 - threshold.x) * dir.x + (node.getZ() + 0.5 - threshold.z) * dir.z;
            if (along >= here + minAhead) {
                if (along < aheadAlong) {
                    aheadAlong = along;
                    bestAhead = node;
                }
            } else if (along > behindAlong) {
                behindAlong = along;
                bestBehind = node;
            }
        }
        BlockPos exit = bestAhead != null ? bestAhead : bestBehind;
        if (exit == null) {
            return null;
        }
        double along = bestAhead != null ? aheadAlong : behindAlong;
        List<Vec3> points = new ArrayList<>();
        List<BlockPos> nodes = new ArrayList<>();
        points.add(pos);
        // Down the centerline to abeam the exit (skipped if we're already about there), then off.
        Vec3 abeam = new Vec3(threshold.x + dir.x * along, threshold.y, threshold.z + dir.z * along);
        if (Math.abs(along - here) > 8 && abeam.distanceTo(top(exit)) > 6) {
            points.add(abeam);
            nodes.add(null);
        }
        for (BlockPos n : paths.get(exit)) {
            points.add(top(n));
            nodes.add(n);
        }
        BlockPos gate = paths.get(exit).get(paths.get(exit).size() - 1);
        return new Exit(new Route(points, g.nodes.get(gate), null, null, null, nodes, -1), along);
    }

    /** Runways whose entrance/exit waypoints have a taxi route to this gate, with the shortest such route's length. */
    public static Map<Runway, Double> runwaysServing(ServerLevel level, String gate) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        RunwayRegistry registry = RunwayRegistry.get(level);
        Map<Runway, Double> out = new HashMap<>();
        BlockPos goal = g.gate(gate);
        if (goal == null) {
            return out;
        }
        for (Map.Entry<BlockPos, Set<BlockPos>> e : g.runwayLinks.entrySet()) {
            List<BlockPos> nodes = astar(g, e.getKey(), Set.of(goal), Set.of());
            if (nodes == null) {
                continue;
            }
            double length = 0;
            for (int i = 1; i < nodes.size(); i++) {
                length += Math.sqrt(nodes.get(i - 1).distSqr(nodes.get(i)));
            }
            for (BlockPos marker : e.getValue()) {
                Runway runway = registry.runwayWithMarker(level.dimension(), marker);
                if (runway != null) {
                    out.merge(runway, length, Math::min);
                }
            }
        }
        return out;
    }

    /**
     * Route to the nearest gate (named waypoint) not in `occupied`, or to the nearest gate at all if
     * every one is occupied. Null if the network doesn't reach a gate.
     */
    @Nullable
    public static Route toNearestGate(ServerLevel level, Vec3 from, java.util.function.Predicate<BlockPos> occupied) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        Set<BlockPos> free = new HashSet<>(), all = new HashSet<>();
        g.nodes.forEach((pos, name) -> {
            if (!name.isEmpty()) {
                all.add(pos);
                if (!occupied.test(pos)) {
                    free.add(pos);
                }
            }
        });
        for (Set<BlockPos> goals : List.of(free, all)) {
            if (goals.isEmpty()) {
                continue;
            }
            Path path = path(level, g, from, goals, Set.of());
            if (path != null) {
                BlockPos gate = path.nodes.get(path.nodes.size() - 1);
                return new Route(path.points, g.nodes.get(gate), null, null, null, path.nodes, -1);
            }
        }
        return null;
    }

    /**
     * Route from where the aircraft is onto a runway, lined up for takeoff. runwayName null = any
     * runway except avoidRunway; picks the shortest taxi.
     */
    @Nullable
    public static Route toRunway(ServerLevel level, Vec3 from, @Nullable String runwayName, @Nullable String avoidRunway) {
        return toRunway(level, from, runwayName, avoidRunway, Set.of());
    }

    @Nullable
    public static Route toRunway(ServerLevel level, Vec3 from, @Nullable String runwayName, @Nullable String avoidRunway, Set<BlockPos> avoid) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        RunwayRegistry registry = RunwayRegistry.get(level);
        Route best = null;
        for (Map.Entry<BlockPos, Set<BlockPos>> e : g.runwayLinks.entrySet()) {
            for (BlockPos marker : e.getValue()) {
                Runway runway = registry.runwayWithMarker(level.dimension(), marker);
                if (runway == null || (runwayName != null && !runway.name().equalsIgnoreCase(runwayName))
                        || (avoidRunway != null && runway.name().equalsIgnoreCase(avoidRunway))) {
                    continue;
                }
                Vec3 start = new Vec3(marker.getX() + 0.5, marker.getY() + 1.0, marker.getZ() + 0.5);
                Vec3 end = start.distanceToSqr(runway.a()) < 1 ? runway.b() : runway.a();
                Path path = path(level, g, from, Set.of(e.getKey()), avoid);
                if (path == null) {
                    continue;
                }
                List<Vec3> points = path.points;
                int holdShort = points.size() - 1; // the entrance waypoint: wait here until the runway is free
                Vec3 takeoffFrom = lineUp(points, start, end, runway.length());
                Route route = new Route(points, runway.name(), runway, takeoffFrom, end, path.nodes, holdShort);
                if (best == null || route.length() < best.length()) {
                    best = route;
                }
            }
        }
        return best;
    }

    /**
     * Already on a runway but not lined up (sideways, facing the wrong way, too near the far end):
     * a short taxi on the runway itself to line up, no taxiway network needed. Takes off the way
     * we're facing if enough runway is left that way, otherwise the other way.
     */
    public static Route lineUpOn(Runway runway, Vec3 pos, double headingYaw) {
        double minimum = runway.length() * ivautopilot.ApConfig.MIN_RUNWAY_FRACTION.get();
        Vec3 start = runway.a(), end = runway.b();
        Vec3 dir = Runway.heading(start, end);
        double facing = Math.abs(net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dir.x, dir.z)) - headingYaw));
        double ahead = runway.length() - ((pos.x - start.x) * dir.x + (pos.z - start.z) * dir.z);
        boolean useAB = facing < 90 ? ahead >= minimum : runway.length() - ahead < minimum;
        if (!useAB) {
            start = runway.b();
            end = runway.a();
        }
        List<Vec3> points = new ArrayList<>();
        points.add(pos);
        Vec3 takeoffFrom = lineUp(points, start, end, runway.length());
        return new Route(points, runway.name(), runway, takeoffFrom, end, List.of(), 0);
    }

    /**
     * Add the points that take the aircraft from the runway entrance onto the runway, lined up for
     * takeoff from `start` toward `end`. It joins the centerline where the taxiway meets it, already
     * facing the takeoff direction, and takes off from there if enough runway is left ahead
     * (takeoff.minRunwayFraction). Otherwise it backtracks along the runway toward `start`, turns
     * around near the end and lines up there. Returns where the takeoff roll starts.
     */
    static Vec3 lineUp(List<Vec3> points, Vec3 start, Vec3 end, double length) {
        Vec3 dir = Runway.heading(start, end);
        Vec3 left = new Vec3(dir.z, 0, -dir.x);
        Vec3 entry = points.get(points.size() - 1);
        double along = Math.max(0, Math.min(length, (entry.x - start.x) * dir.x + (entry.z - start.z) * dir.z));
        double merge = Math.min(15, length * 0.05); // turn onto the centerline over this distance
        double minimum = length * ivautopilot.ApConfig.MIN_RUNWAY_FRACTION.get();
        if (length - along - merge >= minimum) {
            // Join here, facing down the runway.
            points.add(onLine(start, dir, along + merge));
            points.add(onLine(start, dir, along + merge + 40)); // a straight stretch to settle on the heading
            return onLine(start, dir, along);
        }
        // Too close to the far end: backtrack toward the start of the runway, turn around, line up.
        double side = (entry.x - start.x) * left.x + (entry.z - start.z) * left.z >= 0 ? 1 : -1;
        double turn = Math.min(40, length * 0.1);
        points.add(onLine(start, dir, Math.max(turn, along - merge)));            // onto the runway, heading back
        points.add(onLine(start, dir, turn));                                       // back toward the end
        points.add(onLine(start, dir, turn * 0.4).add(left.scale(side * 10)));      // swing wide for the turn-around
        points.add(onLine(start, dir, 0).subtract(left.scale(side * 4)));          // round the end
        points.add(onLine(start, dir, turn + 20));                                  // lined up, facing down the runway
        return start;
    }

    private static Vec3 onLine(Vec3 start, Vec3 dir, double distance) {
        return new Vec3(start.x + dir.x * distance, start.y, start.z + dir.z * distance);
    }

    /** A path through the network: points (starting at the aircraft) and the waypoints they are at. */
    private record Path(List<Vec3> points, List<BlockPos> nodes) {
    }

    /** A way onto the network from where the aircraft is: extra points to roll through first, then the waypoint. */
    private record Start(BlockPos node, List<Vec3> approach, double cost) {
    }

    /**
     * Ways onto the network from here: the nearest waypoint within JOIN_DISTANCE and - if the
     * aircraft is on a runway - every waypoint along that runway (linked to it, or within 40 blocks
     * of its centerline), reached by rolling down the centerline to abeam it and turning off.
     */
    private static List<Start> starts(ServerLevel level, TaxiNetwork.Graph g, Vec3 from) {
        List<Start> out = new ArrayList<>();
        BlockPos near = nearest(g, from);
        if (near != null) {
            out.add(new Start(near, List.of(), Math.hypot(near.getX() + 0.5 - from.x, near.getZ() + 0.5 - from.z)));
        }
        Runway runway = runwayUnder(level, from);
        if (runway == null) {
            return out;
        }
        Vec3 dir = Runway.heading(runway.a(), runway.b());
        double here = (from.x - runway.a().x) * dir.x + (from.z - runway.a().z) * dir.z;
        Set<BlockPos> candidates = new HashSet<>();
        RunwayRegistry registry = RunwayRegistry.get(level);
        g.runwayLinks.forEach((node, markers) -> {
            for (BlockPos marker : markers) {
                Runway r = registry.runwayWithMarker(level.dimension(), marker);
                if (r != null && r.name().equalsIgnoreCase(runway.name())) {
                    candidates.add(node);
                }
            }
        });
        for (BlockPos node : g.nodes.keySet()) {
            double along = (node.getX() + 0.5 - runway.a().x) * dir.x + (node.getZ() + 0.5 - runway.a().z) * dir.z;
            double cross = Math.abs((node.getX() + 0.5 - runway.a().x) * dir.z - (node.getZ() + 0.5 - runway.a().z) * dir.x);
            if (along > -40 && along < runway.length() + 40 && cross < 40 && Math.abs(node.getY() - from.y) < 8) {
                candidates.add(node);
            }
        }
        for (BlockPos node : candidates) {
            double along = (node.getX() + 0.5 - runway.a().x) * dir.x + (node.getZ() + 0.5 - runway.a().z) * dir.z;
            Vec3 abeam = new Vec3(runway.a().x + dir.x * along, from.y, runway.a().z + dir.z * along);
            double off = abeam.distanceTo(top(node));
            List<Vec3> approach = Math.abs(along - here) > 8 && off > 6 ? List.of(abeam) : List.of();
            out.add(new Start(node, approach, Math.abs(along - here) + off));
        }
        return out;
    }

    /** The runway the aircraft is on (anywhere on it, any heading), or null. */
    @Nullable
    public static Runway runwayUnder(ServerLevel level, Vec3 pos) {
        Runway best = null;
        double bestCross = 25;
        for (Runway runway : RunwayRegistry.get(level).all(level.dimension())) {
            Vec3 dir = Runway.heading(runway.a(), runway.b());
            double along = (pos.x - runway.a().x) * dir.x + (pos.z - runway.a().z) * dir.z;
            double cross = Math.abs((pos.x - runway.a().x) * dir.z - (pos.z - runway.a().z) * dir.x);
            if (along > -30 && along < runway.length() + 30 && cross < bestCross && Math.abs(pos.y - Math.min(runway.a().y, runway.b().y)) < 8) {
                best = runway;
                bestCross = cross;
            }
        }
        return best;
    }

    @Nullable
    private static Path path(ServerLevel level, TaxiNetwork.Graph g, Vec3 from, Set<BlockPos> goals, Set<BlockPos> avoid) {
        List<Start> starts = starts(level, g, from);
        if (starts.isEmpty()) {
            return null;
        }
        Map<BlockPos, Double> startCost = new HashMap<>();
        Map<BlockPos, Start> startOf = new HashMap<>();
        for (Start st : starts) {
            if (st.cost < startCost.getOrDefault(st.node, Double.MAX_VALUE)) {
                startCost.put(st.node, st.cost);
                startOf.put(st.node, st);
            }
        }
        List<BlockPos> nodes = astar(g, startCost, goals, avoid);
        if (nodes == null) {
            return null;
        }
        Start start = startOf.get(nodes.get(0));
        List<Vec3> points = new ArrayList<>();
        List<BlockPos> atPoint = new ArrayList<>();
        points.add(from);
        for (Vec3 p : start.approach) {
            points.add(p);
            atPoint.add(null); // a point on the runway, not a waypoint
        }
        for (BlockPos n : nodes) {
            points.add(top(n));
            atPoint.add(n);
        }
        smooth(level, points, atPoint);
        return new Path(points, atPoint);
    }

    /**
     * Take out kinks: a waypoint where the route doubles back sharply (over 100 degrees) over a
     * short distance, or one barely off the straight line between its neighbours - if a plane can
     * roll straight past it (WaypointGenerator.clearPath). Planes otherwise spin round to reach it.
     */
    static void smooth(ServerLevel level, List<Vec3> points, List<BlockPos> atPoint) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < points.size() - 1; i++) {
                Vec3 a = points.get(i - 1), b = points.get(i), c = points.get(i + 1);
                double ab = Math.hypot(b.x - a.x, b.z - a.z), bc = Math.hypot(c.x - b.x, c.z - b.z);
                if (ab < 1.0E-3 || bc < 1.0E-3) {
                    points.remove(i);
                    atPoint.remove(i - 1);
                    changed = true;
                    break;
                }
                double cos = ((b.x - a.x) * (c.x - b.x) + (b.z - a.z) * (c.z - b.z)) / (ab * bc);
                double turn = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
                double off = distanceToSegment(b, a, c);
                boolean kink = turn > 100 && Math.min(ab, bc) < 20;
                boolean straight = off < 2.5;
                if ((kink || straight) && WaypointGenerator.clearPath(level, BlockPos.containing(a), BlockPos.containing(c))) {
                    points.remove(i);
                    atPoint.remove(i - 1);
                    changed = true;
                    break;
                }
            }
        }
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z, len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2));
        return Math.hypot(p.x - a.x - t * dx, p.z - a.z - t * dz);
    }

    @Nullable
    static BlockPos nearest(TaxiNetwork.Graph g, Vec3 from) {
        BlockPos best = null;
        double bestD = JOIN_DISTANCE * JOIN_DISTANCE;
        for (BlockPos n : g.nodes.keySet()) {
            double dx = n.getX() + 0.5 - from.x, dz = n.getZ() + 0.5 - from.z, dy = Math.abs(n.getY() - from.y);
            double d = dx * dx + dz * dz;
            if (d < bestD && dy < 12) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    @Nullable
    static List<BlockPos> astar(TaxiNetwork.Graph g, BlockPos start, Set<BlockPos> goals, Set<BlockPos> avoid) {
        return astar(g, Map.of(start, 0.0), goals, avoid);
    }

    /** A* from several starting waypoints, each with a cost to reach it. The path returned begins at the start used. */
    @Nullable
    static List<BlockPos> astar(TaxiNetwork.Graph g, Map<BlockPos, Double> starts, Set<BlockPos> goals, Set<BlockPos> avoid) {
        record Entry(BlockPos pos, double f) {
        }
        Map<BlockPos, Double> cost = new HashMap<>();
        Map<BlockPos, BlockPos> from = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        starts.forEach((start, c) -> {
            cost.put(start, c);
            open.add(new Entry(start, c + h(start, goals)));
        });
        Set<BlockPos> closed = new HashSet<>();
        while (!open.isEmpty()) {
            BlockPos cur = open.poll().pos;
            if (!closed.add(cur)) {
                continue;
            }
            if (goals.contains(cur)) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos p = cur; p != null; p = from.get(p)) {
                    path.add(p);
                }
                Collections.reverse(path);
                return path;
            }
            for (BlockPos next : g.neighbours(cur)) {
                if (avoid.contains(next) && !goals.contains(next)) {
                    continue;
                }
                double c = cost.get(cur) + Math.sqrt(cur.distSqr(next));
                if (c < cost.getOrDefault(next, Double.MAX_VALUE)) {
                    cost.put(next, c);
                    from.put(next, cur);
                    open.add(new Entry(next, c + h(next, goals)));
                }
            }
        }
        return null;
    }

    private static double h(BlockPos p, Set<BlockPos> goals) {
        double best = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            best = Math.min(best, Math.sqrt(p.distSqr(goal)));
        }
        return best;
    }
}
