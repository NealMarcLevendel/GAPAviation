package ivautopilot.nav;

import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Where a NAV-mode flight is going and how it's getting there.
 *
 * Optional takeoff from a departure runway, then a terrain-aware route to the approach ENTRY point
 * of the destination runway (on the extended centerline, a fixed distance out, at approach height),
 * then intercept, glide path, flare, touchdown and rollout (see Landing).
 */
public final class FlightPlan {
    public enum Phase {
        /** Taxiing out to the departure runway (see Taxi). */
        TAXI,
        TAKEOFF_ROLL, ROTATE, CLIMB_OUT, ENROUTE,
        /** Turn-straight-turn circuit to the approach entry, arriving lined up with the runway. */
        PATTERN,
        /** Intercepting the extended centerline at approach height. */
        APPROACH,
        /** Established, on the glide path. */
        FINAL,
        /** Last few blocks: idle, raise the nose, touch down. */
        FLARE,
        /** On the runway: brakes, reverse thrust, centerline. */
        ROLLOUT,
        /** Missed approach: full power, climb back to approach height. */
        GO_AROUND,
        /** Fly out to a point well out on the approach side to try again. */
        REPOSITION,
        HOLDING
    }

    /** Departure, when the flight started on the ground: runway start/end points and rotation speed. */
    public Runway departure;
    public Vec3 departureStart, departureEnd;
    /** Rotation speed in indicated blocks/second, computed from the aircraft's lift at the start of the roll. */
    public double rotateSpeed;
    public int phaseTicks;
    /** Learned in flight: real stall speed / stall speed from the lift model (raised whenever stall protection trips). */
    public double stallScale = 1;
    /** Rollout: deceleration (blocks/s^2) that stops the aircraft at the end of the runway, from touchdown. */
    public double rolloutDecel;
    /** Rollout: slowed to taxi speed, now taxiing to the end of the runway. */
    public boolean rolloutTaxiing;
    /** Rollout: how far down the runway (from the threshold) the exit to the gate is; NaN = none / not looked yet. */
    public double exitAlong = Double.NaN;
    public boolean exitChecked;
    /** Takeoff: past the far end of the departure runway (banking allowed from then on). */
    public boolean clearedDeparture;
    /** Landing: approach attempts that ended in a go-around. */
    public int goArounds;
    /** Landing: where to fly to before trying the approach again. */
    public Vec3 repositionPoint;
    /** Landing: the circuit being flown to the approach entry, and how long we've been off it. */
    public Pattern pattern;
    public int patternOffTicks;
    /** Traffic: holding at the approach entry until the runway is free (vs. holding after giving up). */
    public boolean waitingForRunway;
    /** Traffic: place in this runway's arrival queue (0 = next to land); stacks the hold 30 blocks per place. */
    public int queuePosition;

    public final Runway runway;
    /** Runway end we'll land at, the other end, and the approach entry point beyond the threshold. */
    public final Vec3 threshold, farEnd, entry;
    public final double cruiseAltitude, cruiseSpeed;

    public Phase phase = Phase.ENROUTE;
    public volatile List<RoutePlanner.Waypoint> route;
    public int waypointIndex;
    public Vec3 legStart;
    public CompletableFuture<List<RoutePlanner.Waypoint>> pendingPlan;
    public Vec3 pendingPlanStart;
    public int replanTimer;
    /** Terrain guard: temporary minimum altitude, and how many more ticks it applies. */
    public double guardAltitude;
    public int guardTicks;

    public FlightPlan(Runway runway, Vec3 threshold, Vec3 farEnd, double approachDistance, double approachHeight,
                      double cruiseAltitude, double cruiseSpeed) {
        this.runway = runway;
        this.threshold = threshold;
        this.farEnd = farEnd;
        Vec3 inbound = Runway.heading(threshold, farEnd);
        Vec3 out = threshold.subtract(inbound.scale(approachDistance));
        this.entry = new Vec3(out.x, threshold.y + approachHeight, out.z);
        this.cruiseAltitude = cruiseAltitude;
        this.cruiseSpeed = cruiseSpeed;
    }

    public void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    /** Destination runway, landing at the end that's closer to the aircraft (so we approach from our side). */
    public static FlightPlan to(Runway runway, Vec3 aircraft, double approachDistance, double approachHeight,
                                double cruiseAltitude, double cruiseSpeed) {
        Vec3 inboundA = Runway.heading(runway.a(), runway.b());
        Vec3 inboundB = Runway.heading(runway.b(), runway.a());
        Vec3 entryA = runway.a().subtract(inboundA.scale(approachDistance));
        Vec3 entryB = runway.b().subtract(inboundB.scale(approachDistance));
        boolean landAtA = horizontal(aircraft, entryA) <= horizontal(aircraft, entryB);
        return landAtA
                ? new FlightPlan(runway, runway.a(), runway.b(), approachDistance, approachHeight, cruiseAltitude, cruiseSpeed)
                : new FlightPlan(runway, runway.b(), runway.a(), approachDistance, approachHeight, cruiseAltitude, cruiseSpeed);
    }

    public static double horizontal(Vec3 p, Vec3 q) {
        return Math.sqrt((p.x - q.x) * (p.x - q.x) + (p.z - q.z) * (p.z - q.z));
    }
}
