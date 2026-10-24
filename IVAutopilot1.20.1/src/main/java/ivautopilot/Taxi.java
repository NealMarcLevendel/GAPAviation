package ivautopilot;

import ivautopilot.nav.FlightPlan;
import ivautopilot.taxi.TaxiPlanner;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import mcinterface1201.WrapperWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ivautopilot.Takeoff.setIf;

/**
 * Taxiing along a route through the taxiway network: nose-wheel steering toward a point a little
 * way ahead on the route, taxi speed with slowing for corners and for the end, then either park
 * (gate) or line up on the runway - and, if a flight is planned, go straight into the takeoff roll.
 */
final class Taxi {
    /** An aircraft's current taxi. */
    static final class Plan {
        TaxiPlanner.Route route;
        int index = 1;
        int stuckTicks;
        /** Traffic: ticks spent stopped for a waypoint another aircraft holds (re-route after 20 s). */
        int blockedTicks;
        /** Traffic: we hold the runway at the end of this route. */
        boolean cleared;

        Plan(TaxiPlanner.Route route) {
            this.route = route;
        }

        boolean toRunway() {
            return route.runway() != null;
        }

        /** Remaining route as points, starting at the aircraft. */
        List<Vec3> remaining(Vec3 pos) {
            List<Vec3> out = new java.util.ArrayList<>();
            out.add(pos);
            out.addAll(route.points().subList(Math.min(index, route.points().size()), route.points().size()));
            return out;
        }
    }

    private Taxi() {
    }

    /** One tick. Returns null to carry on, "arrived" when done, or a message key if it stopped early. */
    @Nullable
    static String tick(ServerLevel level, EntityVehicleF_Physics aircraft, ApServer.State state, Plan plan) {
        if (aircraft.lastController != null) {
            Takeoff.releaseInputs(aircraft);
            return "msg.ivautopilot.taxi_cancelled";
        }
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        List<Vec3> pts = plan.route.points();
        double speed = aircraft.indicatedSpeed;

        setIf(aircraft.parkingBrakeVar, 0);
        setIf(aircraft.retractGearVar, 0);
        setIf(aircraft.flapDesiredAngleVar, plan.toRunway() && state.plan != null ? Takeoff.takeoffFlaps(aircraft) : Takeoff.flapNotch(aircraft, 0));
        if (!Takeoff.startEngines(aircraft)) {
            setIf(aircraft.throttleVar, 0);
            setIf(aircraft.brakeVar, 1);
            state.info = "TAXI: STARTING ENGINES";
            return null;
        }

        // Move along the route: next point reached when close, or when we've passed it.
        while (plan.index < pts.size() - 1) {
            Vec3 a = pts.get(plan.index - 1), b = pts.get(plan.index);
            Vec3 seg = flat(b.subtract(a));
            double len = seg.length();
            double along = len < 1.0E-3 ? len : flat(pos.subtract(a)).dot(seg) / len;
            if (horizontal(pos, b) < 4 || along > len) {
                plan.index++;
            } else {
                break;
            }
        }
        double remaining = horizontal(pos, pts.get(plan.index));
        for (int i = plan.index; i < pts.size() - 1; i++) {
            remaining += horizontal(pts.get(i), pts.get(i + 1));
        }

        // Traffic: how far we may go before we have to stop.
        Stop stop = traffic(level, aircraft, plan, pos);
        pts = plan.route.points(); // may have been re-routed
        if (stop.reason != null && stop.distance < 1 && Math.abs(speed) < 1 && stop.reroutable) {
            if (++plan.blockedTicks > 400) {
                plan.blockedTicks = 0;
                if (reroute(level, aircraft, state, plan, pos)) {
                    ApServer.notifyRiders(level.getServer(), aircraft, "msg.ivautopilot.taxi_rerouted");
                    return null;
                }
            }
        } else {
            plan.blockedTicks = 0;
        }

        // Steer toward a carrot point a little way ahead along the route.
        double lookahead = 6 + Math.max(0, speed) * 0.8;
        Vec3 carrot = pointAhead(pos, pts, plan.index, lookahead);
        double desired = ApServer.yawOf(carrot.subtract(pos));
        double headingError = Mth.wrapDegrees(desired - ApServer.headingOf(aircraft));
        Takeoff.steerHeading(aircraft, state, desired);

        // Speed: taxi speed, slower for corners, a sharp heading error, and the end of the route.
        double target = ApConfig.TAXI_SPEED.get();
        double toCorner = horizontal(pos, pts.get(plan.index));
        if (plan.index < pts.size() - 1) {
            double turn = Math.abs(Mth.wrapDegrees(ApServer.yawOf(pts.get(plan.index + 1).subtract(pts.get(plan.index)))
                    - ApServer.yawOf(pts.get(plan.index).subtract(plan.index > 0 ? pts.get(plan.index - 1) : pos))));
            if (turn > 30) {
                target = Math.min(target, 3.5 + Math.max(0, toCorner - 6) * 0.25);
            }
        }
        if (Math.abs(headingError) > 25) {
            target = Math.min(target, 3);
        }
        // Drifting wide of the route (a long jet in a tight corner): slow right down. MTS nose-wheel
        // steering loses most of its effect at speed (about 40% at taxi speed, 85% at walking pace).
        Vec3 a0 = pts.get(plan.index - 1), b0 = pts.get(plan.index);
        Vec3 segDir = flat(b0.subtract(a0));
        if (segDir.lengthSqr() > 1.0E-6) {
            Vec3 u = segDir.normalize();
            double cross = Math.abs((pos.x - a0.x) * u.z - (pos.z - a0.z) * u.x);
            if (cross > 3) {
                target = Math.min(target, 2);
            }
        }
        target = Math.min(target, Math.sqrt(2 * 1.2 * Math.max(0, remaining - 1.5)));
        target = Math.min(target, Math.sqrt(2 * 1.2 * Math.max(0, stop.distance)));

        boolean stuck = speed < 0.3 && target > 2;
        plan.stuckTicks = stuck ? plan.stuckTicks + 1 : 0;
        double maxThrottle = plan.stuckTicks > 60 ? 0.6 : 0.35; // heavy jets need a bit more to break away
        double error = target - speed;
        setIf(aircraft.throttleVar, error > 0 ? Mth.clamp(0.08 + 0.05 * error, 0, maxThrottle) : 0);
        setIf(aircraft.brakeVar, speed > target + 0.5 ? Mth.clamp((speed - target) * 0.4, 0, 1) : remaining < 1.5 || stop.distance < 1 ? 1 : 0);
        if (stop.distance < 1) {
            setIf(aircraft.throttleVar, 0);
        }

        state.info = String.format("TAXI to %s  %.0f blocks  %.0f km/h", plan.route.target(), remaining, speed * 3.6);
        if (stop.reason != null && stop.distance < 40) {
            state.info += "  WAITING: " + stop.reason;
        }

        // Done: stopped at the end - or, rolling onto the runway, at/past the lined-up point (the takeoff
        // roll takes it from there; never circle back for it). A gate overshot by a little counts too.
        Vec3 lastA = pts.get(pts.size() - 2), lastB = pts.get(pts.size() - 1);
        Vec3 lastSeg = flat(lastB.subtract(lastA));
        boolean passedEnd = plan.index == pts.size() - 1 && lastSeg.lengthSqr() > 1.0E-6
                && flat(pos.subtract(lastA)).dot(lastSeg) / lastSeg.length() > lastSeg.length() - 1;
        boolean done = remaining < 2.5 && Math.abs(speed) < 0.7
                || plan.toRunway() && (remaining < 4 || passedEnd) && Math.abs(speed) < 6
                || !plan.toRunway() && passedEnd && remaining < 6;
        if (done) {
            setIf(aircraft.throttleVar, 0);
            setIf(aircraft.brakeVar, 1);
            Takeoff.releaseInputs(aircraft);
            return "arrived";
        }
        return null;
    }

    /** Where traffic makes us stop: distance (blocks we may still roll), why, and whether re-routing could help. */
    private record Stop(double distance, @Nullable String reason, boolean reroutable) {
        static final Stop NONE = new Stop(Double.MAX_VALUE, null, false);

        Stop min(double d, String why, boolean canReroute) {
            return d < distance ? new Stop(d, why, canReroute) : this;
        }
    }

    /**
     * Traffic rules on the ground: hold the waypoints within 80 blocks ahead (stop short of one another
     * aircraft holds), hold short of the runway until we have it, and stop behind anything on our path.
     */
    private static Stop traffic(ServerLevel level, EntityVehicleF_Physics aircraft, Plan plan, Vec3 pos) {
        TaxiPlanner.Route route = plan.route;
        List<Vec3> pts = route.points();
        UUID self = aircraft.uniqueUUID;
        double radius = radius(aircraft);
        Stop stop = Stop.NONE;

        // Distance along the route to each point ahead.
        double[] dist = new double[pts.size()];
        dist[plan.index] = horizontal(pos, pts.get(plan.index));
        for (int i = plan.index + 1; i < pts.size(); i++) {
            dist[i] = dist[i - 1] + horizontal(pts.get(i - 1), pts.get(i));
        }

        // Waypoints: keep the one we're crossing, take the ones ahead.
        Set<BlockPos> keep = new HashSet<>();
        BlockPos behind = route.nodeAt(plan.index - 1);
        if (behind != null && horizontal(pos, pts.get(plan.index - 1)) < radius + 10) {
            TrafficControl.holdNode(level, behind, self);
            keep.add(behind);
        }
        for (int i = plan.index; i < pts.size() && dist[i] < 80 + radius; i++) {
            BlockPos node = route.nodeAt(i);
            if (node == null) {
                continue;
            }
            if (!TrafficControl.holdNode(level, node, self)) {
                stop = stop.min(dist[i] - radius - 8, "waypoint ahead in use", true);
                break;
            }
            keep.add(node);
        }
        TrafficControl.releaseNodesExcept(level, self, keep);

        // Hold short: the runway must be ours before we enter it.
        int holdShort = route.holdShortIndex();
        // holdShort 0: already on the runway (lining up on it): wait right here until it's ours.
        boolean beforeRunway = holdShort >= plan.index || (holdShort == 0 && plan.index == 1);
        if (route.runway() != null && beforeRunway && !plan.cleared) {
            double toHold = holdShort == 0 ? 0 : dist[holdShort] - radius - 3;
            if (toHold < 120 && (aircraft.ticksExisted % 10 == 0 || TrafficControl.holds(level, route.runway(), self))) {
                plan.cleared = TrafficControl.request(level, route.runway(), self, false);
            }
            if (!plan.cleared && toHold < 120) {
                stop = stop.min(toHold, (holdShort == 0 ? "waiting for " : "holding short of ") + route.runway().name(), false);
            }
        }

        // Anything physically in the way on the path ahead.
        double blocked = obstacleAhead(level, aircraft, pts, plan.index, pos, 60 + radius);
        if (blocked < Double.MAX_VALUE) {
            stop = stop.min(blocked - radius - 4, "traffic ahead", false);
        }
        return stop;
    }

    /** Rough horizontal radius of a vehicle (MTS boxes are axis-aligned, so this errs a little large). */
    static double radius(EntityVehicleF_Physics vehicle) {
        return 0.75 * Math.max(vehicle.encompassingBox.widthRadius, vehicle.encompassingBox.depthRadius);
    }

    /**
     * Distance along the route to the first point where another vehicle (in front of us) would be in
     * the way, measured to that vehicle's edge; MAX_VALUE if clear.
     */
    private static double obstacleAhead(ServerLevel level, EntityVehicleF_Physics aircraft, List<Vec3> pts, int index, Vec3 pos, double range) {
        double yaw = Math.toRadians(ApServer.headingOf(aircraft));
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        List<double[]> others = new ArrayList<>();
        for (EntityVehicleF_Physics other : WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class)) {
            if (other == aircraft || !other.isValid) {
                continue;
            }
            double dx = other.position.x - pos.x, dz = other.position.z - pos.z;
            double r = radius(other);
            if (Math.abs(other.position.y - pos.y) > 10 || dx * dx + dz * dz > Math.pow(range + r + 20, 2)
                    || dx * forward.x + dz * forward.z <= 0) {
                continue;
            }
            others.add(new double[]{other.position.x, other.position.z, r});
        }
        if (others.isEmpty()) {
            return Double.MAX_VALUE;
        }
        Vec3 from = pos;
        double travelled = 0;
        for (int i = index; i < pts.size() && travelled < range; i++) {
            Vec3 to = pts.get(i);
            double len = horizontal(from, to);
            for (double d = 0; d < len && travelled + d < range; d += 2) {
                double x = from.x + (to.x - from.x) * d / Math.max(len, 1.0E-6), z = from.z + (to.z - from.z) * d / Math.max(len, 1.0E-6);
                for (double[] o : others) {
                    double ox = o[0] - x, oz = o[1] - z;
                    if (ox * ox + oz * oz < (o[2] + 2) * (o[2] + 2)) {
                        return travelled + d; // this point is just clear of its edge
                    }
                }
            }
            travelled += len;
            from = to;
        }
        return Double.MAX_VALUE;
    }

    /** Find another way round the waypoints other aircraft hold. True if the route changed. */
    private static boolean reroute(ServerLevel level, EntityVehicleF_Physics aircraft, ApServer.State state, Plan plan, Vec3 pos) {
        Set<BlockPos> avoid = TrafficControl.nodesHeldByOthers(level, aircraft.uniqueUUID);
        TaxiPlanner.Route old = plan.route;
        TaxiPlanner.Route route = old.runway() != null
                ? TaxiPlanner.toRunway(level, pos, old.runway().name(), null, avoid)
                : TaxiPlanner.toGate(level, pos, old.target(), avoid);
        if (route == null) {
            return false;
        }
        plan.route = route;
        plan.index = 1;
        if (route.runway() != null && state.plan != null) {
            state.plan.departureStart = route.runwayStart();
            state.plan.departureEnd = route.runwayEnd();
        }
        return true;
    }

    /** Point `distance` ahead along the route from the aircraft's projection on the current segment. */
    private static Vec3 pointAhead(Vec3 pos, List<Vec3> pts, int index, double distance) {
        Vec3 a = pts.get(Math.max(0, index - 1)), b = pts.get(index);
        Vec3 seg = flat(b.subtract(a));
        double len = seg.length();
        double along = len < 1.0E-3 ? 0 : Mth.clamp(flat(pos.subtract(a)).dot(seg) / len, 0, len);
        double left = distance;
        Vec3 from = len < 1.0E-3 ? a : a.add(seg.scale(along / len));
        for (int i = index; i < pts.size(); i++) {
            Vec3 to = pts.get(i);
            double d = horizontal(from, to);
            if (d >= left) {
                return from.add(flat(to.subtract(from)).normalize().scale(left));
            }
            left -= d;
            from = to;
        }
        return pts.get(pts.size() - 1);
    }

    private static Vec3 flat(Vec3 v) {
        return new Vec3(v.x, 0, v.z);
    }

    private static double horizontal(Vec3 p, Vec3 q) {
        return FlightPlan.horizontal(p, q);
    }
}
