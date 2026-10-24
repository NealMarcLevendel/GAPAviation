package ivautopilot;

import ivautopilot.nav.Airport;
import ivautopilot.nav.AirportRegistry;
import ivautopilot.nav.FlightPlan;
import ivautopilot.nav.RoutePlanner;
import ivautopilot.nav.Runway;
import ivautopilot.nav.Terrain;
import mcinterface1201.BuilderEntityLinkedSeat;
import mcinterface1201.WrapperWorld;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.components.AEntityA_Base;
import minecrafttransportsimulator.entities.instances.AEntityVehicleE_Powered;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartSeat;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server side: per-aircraft autopilot state and the control loops, run every tick after MTS has
 * updated the aircraft. The autopilot flies only through TRIM (elevator and aileron) and the
 * throttle, so the pilot's own stick input still adds on top and can always override it.
 *
 * Modes: PITCH hold, ALTITUDE hold, NAV (fly a terrain-aware route to a runway's approach entry
 * point and hold there).
 *
 * Sign conventions (MTS): orientation.angles.x is positive NOSE DOWN (pitch-up = -angles.x); positive
 * elevator/trim pitches the nose UP; positive aileron increases angles.z. Bank direction and turn
 * direction are worked out geometrically, so they don't depend on the roll-angle convention.
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ApServer {
    /** Autopilot state of one aircraft. */
    static final class State {
        ResourceKey<Level> dimension;
        ApMode mode = ApMode.OFF;
        boolean autoTrim;
        double targetPitch, targetAltitude, targetSpeed;
        double pitchIntegral, bankIntegral, throttleIntegral = Double.NaN;
        double lastPitch = Double.NaN, lastBank = Double.NaN, lastY = Double.NaN;
        double aileronSign = 1;
        // Takeoff: elevator-input integral, rudder sign (learned), last heading for the yaw rate.
        double elevatorIntegral, rudderSign = 1, lastHeading = Double.NaN;
        // Landing: pitch integral of the vertical-speed loop.
        double vsIntegral;
        // No-bank zones: wings level, heading held with rudder trim (lockHeading NaN = follow the nav).
        boolean bankLocked;
        String lockReason = "";
        int missingTicks;
        /** Taxiing (to a gate, or out to the departure runway before a flight). */
        @Nullable Taxi.Plan taxi;
        // Stall protection: filtered angle of attack, whether protection is active, and the speed command ramp.
        double aoa = Double.NaN;
        boolean stallProtect;
        double speedCommand = Double.NaN;
        double commandPitch;
        double lockHeading = Double.NaN, lastYawHeading = Double.NaN, aeroRudderSign = 1;
        // Bank control: adaptive gain (lowered when this aircraft overshoots / oscillates), and the oscillation detector.
        double rollGain = 1, lastBankError;
        int rollGainCooldown, bankErrorFlips, flipWindow;
        @Nullable FlightPlan plan;
        // Traffic: extra altitude to stay clear of another autopilot aircraft nearby, and how much longer to keep it.
        double separation;
        int separationTicks;
        /** Delayed start: ticks left to wait (parked) before the taxi / takeoff begins. */
        int delayTicks;
        String info = "";
        boolean dirty = true;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final double PITCH_STEP = 1.0, ALTITUDE_STEP = 5.0;
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IV Autopilot route planner");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------------------------

    static void handleCommand(ServerPlayer player, ApNetwork.Action action) {
        EntityVehicleF_Physics aircraft = controlledAircraft(player);
        if (aircraft == null) {
            aircraft = engagedAircraftAboard(player); // walking around the cabin: still in charge of the autopilot
        }
        if (aircraft == null) {
            player.displayClientMessage(Component.translatable("msg.ivautopilot.not_pilot"), true);
            return;
        }
        State state = stateFor(aircraft, player);
        switch (action) {
            case CYCLE_MODE -> setMode(aircraft, state, state.mode.next());
            case TOGGLE_AUTO_TRIM -> state.autoTrim = !state.autoTrim;
            case TARGET_UP, TARGET_DOWN -> {
                int sign = action == ApNetwork.Action.TARGET_UP ? 1 : -1;
                if (state.mode == ApMode.PITCH) {
                    state.targetPitch = Mth.clamp(state.targetPitch + sign * PITCH_STEP, -ApConfig.MAX_PITCH.get(), ApConfig.MAX_PITCH.get());
                } else if (state.mode == ApMode.ALTITUDE) {
                    state.targetAltitude += sign * ALTITUDE_STEP;
                }
            }
        }
        state.dirty = true;
    }

    /** /autopilot fly: start NAV to a runway. Returns an error message key, or null on success. */
    @Nullable
    static String startNav(ServerPlayer player, Runway runway, @Nullable Double cruiseAltitude, @Nullable Double cruiseSpeed) {
        EntityVehicleF_Physics aircraft = controlledAircraft(player);
        if (aircraft == null) {
            return "msg.ivautopilot.not_pilot";
        }
        Takeoff.Departure departure = null;
        ivautopilot.taxi.TaxiPlanner.Route taxiOut = null;
        if (isOnGround(aircraft)) {
            departure = Takeoff.findDeparture(player.serverLevel(), aircraft);
            if (departure == null) {
                // Not on a runway: taxi out to the nearest one (by taxi distance) that isn't the destination.
                Vec3 here = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
                Runway under = runwayUnder(player.serverLevel(), aircraft);
                if (under != null) {
                    // On a runway, just not lined up: line up on it right here.
                    taxiOut = ivautopilot.taxi.TaxiPlanner.lineUpOn(under, here, headingOf(aircraft));
                } else {
                    taxiOut = ivautopilot.taxi.TaxiPlanner.toRunway(player.serverLevel(), here, null, runway.name());
                }
                if (taxiOut == null) {
                    return "msg.ivautopilot.not_lined_up";
                }
                departure = new Takeoff.Departure(taxiOut.runway(), taxiOut.runwayStart(), taxiOut.runwayEnd());
            }
            if (departure.runway().name().equals(runway.name())) {
                return "msg.ivautopilot.same_runway";
            }
        }
        State state = stateFor(aircraft, player);
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        // From the ground, cruise at least 150 above the departure as well as the destination.
        double floor = Math.max(runway.a().y, runway.b().y) + 150;
        double alt = cruiseAltitude != null ? cruiseAltitude : Math.max(departure != null ? pos.y + 150 : pos.y, floor);
        double speed = cruiseSpeed != null ? cruiseSpeed : Math.max(aircraft.indicatedSpeed, ApConfig.DEFAULT_CRUISE_SPEED.get());
        FlightPlan plan = FlightPlan.to(runway, pos, ApConfig.APPROACH_DISTANCE.get(), ApConfig.APPROACH_HEIGHT.get(), alt, speed);
        if (cruiseAltitude == null && FlightPlan.horizontal(pos, plan.entry) >= ApConfig.PATTERN_DISTANCE.get() * 1.5) {
            // Longer trip: climb higher the further we go, up to this kind of plane's cruise ceiling (above the clouds for jets).
            double ground = Math.max(departure != null ? departure.runway().a().y : pos.y - 150, floor - 150);
            double byDistance = ground + FlightPlan.horizontal(pos, plan.entry) / ApConfig.TRIP_PER_BLOCK.get();
            double cruise = Math.min(byDistance, cruiseCeiling(aircraft));
            if (cruise > alt) {
                alt = cruise;
                plan = FlightPlan.to(runway, pos, ApConfig.APPROACH_DISTANCE.get(), ApConfig.APPROACH_HEIGHT.get(), alt, speed);
            }
        }
        if (cruiseAltitude == null && FlightPlan.horizontal(pos, plan.entry) < ApConfig.PATTERN_DISTANCE.get() * 1.5) {
            // Short hop: no point climbing to a cruise altitude, fly the circuit at circuit height.
            double circuit = plan.entry.y + 20;
            alt = departure != null ? circuit : Math.max(pos.y, circuit);
            plan = FlightPlan.to(runway, pos, ApConfig.APPROACH_DISTANCE.get(), ApConfig.APPROACH_HEIGHT.get(), alt, speed);
        }
        setMode(aircraft, state, ApMode.NAV);
        state.plan = plan;
        if (departure != null) {
            plan.departure = departure.runway();
            plan.departureStart = departure.start();
            plan.departureEnd = departure.end();
            plan.rotateSpeed = 0;
            plan.setPhase(FlightPlan.Phase.TAKEOFF_ROLL);
            state.elevatorIntegral = 0;
            state.rudderSign = 1;
            state.lastHeading = Double.NaN;
            // Hand the flight controls to us: MTS's client only re-centres inputs for the last controller,
            // and it becomes non-null again the moment the pilot touches the controls (= takeover).
            aircraft.lastController = null;
            if (taxiOut != null) {
                plan.setPhase(FlightPlan.Phase.TAXI);
                state.taxi = new Taxi.Plan(taxiOut);
            }
        }
        state.targetSpeed = speed;
        state.dirty = true;
        return null;
    }

    static void stop(ServerPlayer player) {
        EntityVehicleF_Physics aircraft = controlledAircraft(player);
        if (aircraft == null) {
            aircraft = engagedAircraftAboard(player);
        }
        if (aircraft != null && STATES.containsKey(aircraft.uniqueUUID)) {
            State state = STATES.get(aircraft.uniqueUUID);
            setMode(aircraft, state, ApMode.OFF);
            state.dirty = true;
        }
    }

    private static State stateFor(EntityVehicleF_Physics aircraft, ServerPlayer player) {
        State state = STATES.computeIfAbsent(aircraft.uniqueUUID, k -> new State());
        state.dimension = player.level().dimension();
        return state;
    }

    private static void setMode(EntityVehicleF_Physics aircraft, State state, ApMode mode) {
        TrafficControl.releaseAll(aircraft.uniqueUUID);
        state.delayTicks = 0;
        state.separation = 0;
        state.separationTicks = 0;
        if (state.taxi != null) {
            Takeoff.releaseInputs(aircraft);
            state.taxi = null;
        }
        if (state.plan != null && (isTakeoffPhase(state.plan.phase) || Landing.isHandsOnPhase(state.plan.phase))) {
            Takeoff.releaseInputs(aircraft); // leaving a takeoff/flare: centre the stick and rudder we were holding
        }
        state.mode = mode;
        // Bumpless engage: integrators start from the current trim / throttle.
        state.pitchIntegral = aircraft.elevatorTrimVar.currentValue;
        state.bankIntegral = aircraft.aileronTrimVar.currentValue;
        state.throttleIntegral = Double.NaN;
        state.speedCommand = Double.NaN;
        state.lastPitch = state.lastBank = state.lastY = Double.NaN;
        state.targetPitch = Math.round(pitchUp(aircraft));
        state.vsIntegral = pitchUp(aircraft);
        state.targetAltitude = Math.round(aircraft.position.y);
        if (mode != ApMode.NAV) {
            state.plan = null;
        }
        if (mode != ApMode.OFF && aircraft.autopilotValueVar.isActive) {
            aircraft.autopilotValueVar.setTo(0, true); // don't fight MTS's own autopilot
        }
        if (mode == ApMode.OFF) {
            // Hand the ailerons back level.
            setTrim(aircraft.aileronTrimVar, 0);
            setTrim(aircraft.rudderTrimVar, 0);
        }
    }

    /** Plane tracker entry for this aircraft: where it is, what the autopilot is doing, and its planned path. */
    public static ivautopilot.track.TrackedAircraft trackInfo(ServerLevel level, EntityVehicleF_Physics aircraft) {
        State state = STATES.get(aircraft.uniqueUUID);
        FlightPlan plan = state == null || state.mode != ApMode.NAV ? null : state.plan;
        double speed = aircraft.indicatedSpeed;
        String status;
        if (state == null || state.mode == ApMode.OFF) {
            status = Math.abs(speed) > 2 || !isOnGround(aircraft) ? "MANUAL" : "PARKED";
        } else {
            status = switch (state.mode) {
                case NAV -> "AUTOPILOT";
                case PITCH -> "AP PITCH";
                case ALTITUDE -> "AP ALT";
                default -> "MANUAL";
            };
        }
        String name = aircraft.definition.general != null && aircraft.definition.general.name != null
                ? aircraft.definition.general.name : aircraft.definition.systemName;
        float bearing = (float) (((headingOf(aircraft) + 180) % 360 + 360) % 360);
        float[] path = new float[0], runway = new float[0];
        String phase = "", destination = "";
        float eta = -1;
        if (state != null && state.mode == ApMode.NAV && state.taxi != null) {
            List<Vec3> rest = state.taxi.remaining(new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z));
            float[] taxiPath = new float[rest.size() * 2];
            for (int i = 0; i < rest.size(); i++) {
                taxiPath[2 * i] = (float) rest.get(i).x;
                taxiPath[2 * i + 1] = (float) rest.get(i).z;
            }
            if (plan == null) {
                return new ivautopilot.track.TrackedAircraft(aircraft.uniqueUUID, name, level.dimension().location().toString(),
                        (float) aircraft.position.x, (float) aircraft.position.y, (float) aircraft.position.z, bearing, (float) (speed * 3.6),
                        "AUTOPILOT", "TAXI", state.taxi.route.target(), -1, true, taxiPath, new float[0]);
            }
            path = taxiPath;
            runway = new float[]{(float) plan.threshold.x, (float) plan.threshold.z, (float) plan.farEnd.x, (float) plan.farEnd.z};
            phase = "TAXI";
            destination = plan.runway.name();
        } else if (plan != null) {
            path = plannedPath(aircraft, plan);
            runway = new float[]{(float) plan.threshold.x, (float) plan.threshold.z, (float) plan.farEnd.x, (float) plan.farEnd.z};
            phase = plan.phase.name();
            destination = plan.runway.name();
            double s = secondsToLanding(aircraft, state);
            eta = Double.isNaN(s) ? -1 : (float) s;
        }
        return new ivautopilot.track.TrackedAircraft(aircraft.uniqueUUID, name, level.dimension().location().toString(),
                (float) aircraft.position.x, (float) aircraft.position.y, (float) aircraft.position.z, bearing, (float) (speed * 3.6),
                status, phase, destination, eta, true, path, runway);
    }

    /** The path still to fly, as x,z pairs from the aircraft to the runway (downsampled, at most ~80 points). */
    private static float[] plannedPath(EntityVehicleF_Physics aircraft, FlightPlan plan) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{aircraft.position.x, aircraft.position.z});
        switch (plan.phase) {
            case TAKEOFF_ROLL, ROTATE, CLIMB_OUT -> {
                if (plan.departureEnd != null) {
                    points.add(new double[]{plan.departureEnd.x, plan.departureEnd.z});
                }
                points.add(new double[]{plan.entry.x, plan.entry.z});
                points.add(new double[]{plan.threshold.x, plan.threshold.z});
            }
            case ENROUTE -> {
                List<RoutePlanner.Waypoint> route = plan.route;
                if (route != null) {
                    int step = Math.max(1, (route.size() - plan.waypointIndex) / 40);
                    for (int i = Math.min(plan.waypointIndex, route.size() - 1); i < route.size(); i += step) {
                        points.add(new double[]{route.get(i).x(), route.get(i).z()});
                    }
                    RoutePlanner.Waypoint last = route.get(route.size() - 1);
                    points.add(new double[]{last.x(), last.z()});
                }
                points.add(new double[]{plan.entry.x, plan.entry.z});
                points.add(new double[]{plan.threshold.x, plan.threshold.z});
            }
            case PATTERN -> {
                ivautopilot.nav.Pattern pattern = plan.pattern;
                if (pattern != null) {
                    int step = Math.max(1, (pattern.targetIndex - pattern.nearest) / 60);
                    for (int i = pattern.nearest; i < pattern.targetIndex; i += step) {
                        points.add(new double[]{pattern.xs[i], pattern.zs[i]});
                    }
                }
                points.add(new double[]{plan.entry.x, plan.entry.z});
                points.add(new double[]{plan.threshold.x, plan.threshold.z});
            }
            case APPROACH, FINAL, FLARE -> points.add(new double[]{plan.threshold.x, plan.threshold.z});
            case REPOSITION -> {
                if (plan.repositionPoint != null) {
                    points.add(new double[]{plan.repositionPoint.x, plan.repositionPoint.z});
                }
                points.add(new double[]{plan.entry.x, plan.entry.z});
                points.add(new double[]{plan.threshold.x, plan.threshold.z});
            }
            case GO_AROUND, HOLDING -> {
                points.add(new double[]{plan.entry.x, plan.entry.z});
                points.add(new double[]{plan.threshold.x, plan.threshold.z});
            }
            case ROLLOUT -> points.add(new double[]{plan.farEnd.x, plan.farEnd.z});
        }
        float[] out = new float[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            out[2 * i] = (float) points.get(i)[0];
            out[2 * i + 1] = (float) points.get(i)[1];
        }
        return out;
    }

    /** One taxi tick, and what happens when the taxi ends. */
    private static void taxiTick(ServerLevel level, EntityVehicleF_Physics aircraft, State state) {
        Taxi.Plan taxi = state.taxi;
        String result = Taxi.tick(level, aircraft, state, taxi);
        if (result == null) {
            return;
        }
        FlightPlan plan = state.plan;
        if (result.equals("arrived") && taxi.toRunway() && plan != null) {
            // Lined up: straight into the takeoff roll.
            state.taxi = null;
            plan.setPhase(FlightPlan.Phase.TAKEOFF_ROLL);
            plan.rotateSpeed = 0;
            state.elevatorIntegral = 0;
            state.lastHeading = Double.NaN;
            aircraft.lastController = null;
            notifyRiders(level.getServer(), aircraft, Component.translatable("msg.ivautopilot.taxi_lined_up_takeoff", taxi.route.target()));
            return;
        }
        state.taxi = null;
        if (result.equals("arrived")) {
            Takeoff.setIf(aircraft.parkingBrakeVar, 1);
            Takeoff.setIf(aircraft.brakeVar, 0);
            notifyRiders(level.getServer(), aircraft, Component.translatable(taxi.toRunway()
                    ? "msg.ivautopilot.taxi_lined_up" : "msg.ivautopilot.taxi_parked", taxi.route.target()));
        } else {
            notifyRiders(level.getServer(), aircraft, Component.translatable(result));
        }
        setMode(aircraft, state, ApMode.OFF);
        state.dirty = true;
    }

    /** Gates the aircraft may taxi to after this landing (any free gate), or empty if taxiToGate is off. */
    private static java.util.Set<net.minecraft.core.BlockPos> landingGoals(ServerLevel level, EntityVehicleF_Physics aircraft, State state) {
        return ApConfig.TAXI_TO_GATE.get() ? ivautopilot.taxi.TaxiPlanner.gateGoals(level, null, gateOccupied(level, aircraft)) : java.util.Set.of();
    }

    /** Rollout: where down the runway to turn off for the gate (NaN if there's no exit leading to one). */
    static double exitAlong(ServerLevel level, EntityVehicleF_Physics aircraft, State state, FlightPlan plan) {
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        ivautopilot.taxi.TaxiPlanner.Exit exit = ivautopilot.taxi.TaxiPlanner.exitRoute(level, plan.runway, plan.threshold, plan.farEnd, pos,
                landingGoals(level, aircraft, state), 15);
        return exit == null ? Double.NaN : exit.along();
    }

    /** After landing: vacate the runway, taxiing to the nearest free gate. False if there's no taxi route. */
    private static boolean taxiToGate(ServerLevel level, EntityVehicleF_Physics aircraft, State state, FlightPlan plan, double minAhead) {
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        // Off the runway by the exit that leads to the gate (ahead of us if there is one, else back to it).
        ivautopilot.taxi.TaxiPlanner.Exit exit = ivautopilot.taxi.TaxiPlanner.exitRoute(level, plan.runway, plan.threshold, plan.farEnd, pos,
                landingGoals(level, aircraft, state), minAhead);
        if (exit != null) {
            startGateTaxi(level, aircraft, state, plan, exit.route());
            return true;
        }
        if (!ApConfig.TAXI_TO_GATE.get()) {
            return false;
        }
        ivautopilot.taxi.TaxiPlanner.Route route = ivautopilot.taxi.TaxiPlanner.toNearestGate(level, pos, gateOccupied(level, aircraft));
        if (route == null) {
            return false;
        }
        startGateTaxi(level, aircraft, state, plan, route);
        return true;
    }

    /** True for gates with another vehicle parked on them. */
    private static java.util.function.Predicate<net.minecraft.core.BlockPos> gateOccupied(ServerLevel level, EntityVehicleF_Physics aircraft) {
        List<EntityVehicleF_Physics> others = new ArrayList<>();
        for (EntityVehicleF_Physics v : WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class)) {
            if (v != aircraft && v.isValid) {
                others.add(v);
            }
        }
        return gate -> {
            for (EntityVehicleF_Physics v : others) {
                double dx = v.position.x - gate.getX() - 0.5, dz = v.position.z - gate.getZ() - 0.5;
                double r = Taxi.radius(v) + 5;
                if (dx * dx + dz * dz < r * r && Math.abs(v.position.y - gate.getY()) < 10) {
                    return true;
                }
            }
            return false;
        };
    }

    private static void startGateTaxi(ServerLevel level, EntityVehicleF_Physics aircraft, State state, FlightPlan plan, ivautopilot.taxi.TaxiPlanner.Route route) {
        Takeoff.setIf(aircraft.parkingBrakeVar, 0);
        setMode(aircraft, state, ApMode.NAV); // also gives up the runway
        state.plan = null;
        state.taxi = new Taxi.Plan(route);
        state.lastHeading = Double.NaN;
        aircraft.lastController = null;
        state.dirty = true;
        notifyRiders(level.getServer(), aircraft, Component.translatable("msg.ivautopilot.landed_taxi", plan.runway.name(), route.target()));
    }

    /**
     * Wait this long (parked) before the taxi / takeoff just set up for the player's aircraft starts.
     * Only on the ground. Returns false if there's nothing to delay (airborne).
     */
    static boolean setDelay(ServerPlayer player, int seconds) {
        EntityVehicleF_Physics aircraft = controlledAircraft(player);
        State state = aircraft == null ? null : STATES.get(aircraft.uniqueUUID);
        if (state == null || state.mode != ApMode.NAV || !isOnGround(aircraft) || seconds <= 0) {
            return false;
        }
        state.delayTicks = seconds * 20;
        state.dirty = true;
        return true;
    }

    /** The runway the aircraft is on (anywhere on the strip, any heading), or null. */
    @Nullable
    static Runway runwayUnder(ServerLevel level, EntityVehicleF_Physics aircraft) {
        for (Runway r : ivautopilot.nav.RunwayRegistry.get(level).all(level.dimension())) {
            if (TrafficControl.onStrip(aircraft, r)) {
                return r;
            }
        }
        return null;
    }

    /** /autopilot taxi: to a gate (named waypoint) or onto a runway. Returns an error key, or null. */
    @Nullable
    static String startTaxi(ServerPlayer player, String target) {
        EntityVehicleF_Physics aircraft = controlledAircraft(player);
        if (aircraft == null) {
            return "msg.ivautopilot.not_pilot";
        }
        if (!isOnGround(aircraft)) {
            return "msg.ivautopilot.taxi_on_ground_only";
        }
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        ServerLevel level = player.serverLevel();
        ivautopilot.taxi.TaxiPlanner.Route route;
        if (ivautopilot.taxi.TaxiNetwork.get(level).graph(level.dimension()).gate(target) != null) {
            route = ivautopilot.taxi.TaxiPlanner.toGate(level, pos, target);
        } else if (ivautopilot.nav.RunwayRegistry.get(level).find(level.dimension(), target) != null) {
            // A runway: along the taxiways to its entrance, then onto it and lined up (also from another runway).
            route = ivautopilot.taxi.TaxiPlanner.toRunway(level, pos, target, null);
        } else {
            return "msg.ivautopilot.taxi_no_gate";
        }
        if (route == null) {
            return "msg.ivautopilot.taxi_no_route";
        }
        State state = stateFor(aircraft, player);
        setMode(aircraft, state, ApMode.NAV);
        state.plan = null;
        state.taxi = new Taxi.Plan(route);
        state.lastHeading = Double.NaN;
        aircraft.lastController = null;
        state.dirty = true;
        return null;
    }

    /** True if this aircraft's autopilot is engaged (any mode). */
    static boolean isEngaged(UUID aircraft) {
        State state = STATES.get(aircraft);
        return state != null && state.mode != ApMode.OFF;
    }

    /** An aircraft with the autopilot engaged that this player is aboard (or right next to), out of the seat. */
    @Nullable
    static EntityVehicleF_Physics engagedAircraftAboard(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        EntityVehicleF_Physics best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<UUID, State> entry : STATES.entrySet()) {
            if (entry.getValue().mode == ApMode.OFF || entry.getValue().dimension != level.dimension()) {
                continue;
            }
            EntityVehicleF_Physics aircraft = find(level, entry.getKey());
            if (aircraft != null && isAboard(player, aircraft)) {
                double d = player.position().distanceToSqr(aircraft.position.x, aircraft.position.y, aircraft.position.z);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = aircraft;
                }
            }
        }
        return best;
    }

    /** Riding it, or within its size (walking around inside it). */
    static boolean isAboard(ServerPlayer player, EntityVehicleF_Physics aircraft) {
        if (player.getVehicle() instanceof BuilderEntityLinkedSeat seatBuilder && seatBuilder.entity instanceof PartSeat seat && seat.vehicleOn == aircraft) {
            return true;
        }
        double size = Math.max(aircraft.encompassingBox.widthRadius, Math.max(aircraft.encompassingBox.depthRadius, aircraft.encompassingBox.heightRadius)) + 4;
        return player.position().distanceToSqr(aircraft.position.x, aircraft.position.y, aircraft.position.z) < size * size;
    }

    /** Planned cruise altitude of this aircraft's current flight, or NaN. */
    static double cruiseAltitudeOf(@Nullable EntityVehicleF_Physics aircraft) {
        State state = aircraft == null ? null : STATES.get(aircraft.uniqueUUID);
        return state == null || state.plan == null ? Double.NaN : state.plan.cruiseAltitude;
    }

    enum EngineClass { JET, TURBOPROP, PISTON }

    /** Jet = jet engines and no propellers; turboprop = jet engines turning propellers; piston = no jet power. */
    static EngineClass engineClass(EntityVehicleF_Physics aircraft) {
        boolean jet = false;
        for (minecrafttransportsimulator.entities.instances.PartEngine engine : aircraft.engines) {
            if (engine.definition.engine.jetPowerFactor > 0) {
                jet = true;
            }
        }
        if (!jet) {
            return EngineClass.PISTON;
        }
        for (Object part : aircraft.allParts) {
            if (part instanceof minecrafttransportsimulator.entities.instances.PartPropeller) {
                return EngineClass.TURBOPROP;
            }
        }
        return EngineClass.JET;
    }

    /** Highest altitude this aircraft cruises at, by engine type, relative to the cloud layer. */
    static double cruiseCeiling(EntityVehicleF_Physics aircraft) {
        double above = switch (engineClass(aircraft)) {
            case JET -> ApConfig.JET_ABOVE_CLOUDS.get();
            case TURBOPROP -> ApConfig.TURBOPROP_ABOVE_CLOUDS.get();
            case PISTON -> ApConfig.PISTON_ABOVE_CLOUDS.get();
        };
        return ApConfig.CLOUD_HEIGHT.get() + above;
    }

    /** The aircraft whose controller seat this player is in (fixed-wing only). */
    @Nullable
    static EntityVehicleF_Physics controlledAircraft(ServerPlayer player) {
        if (player.getVehicle() instanceof BuilderEntityLinkedSeat seatBuilder && seatBuilder.entity instanceof PartSeat seat
                && seat.placementDefinition.isController && seat.vehicleOn != null
                && seat.vehicleOn.definition.motorized != null && seat.vehicleOn.definition.motorized.isAircraft) {
            return seat.vehicleOn;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || STATES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, State> entry : new ArrayList<>(STATES.entrySet())) {
            State state = entry.getValue();
            ServerLevel level = state.dimension == null ? null : server.getLevel(state.dimension);
            EntityVehicleF_Physics aircraft = level == null ? null : find(level, entry.getKey());
            if (aircraft == null) {
                // Not found: destroyed or picked up - or just waiting for its chunk to load. Keep the
                // flight plan for a while in case it's the latter.
                if (++state.missingTicks > 600) {
                    STATES.remove(entry.getKey());
                }
                continue;
            }
            state.missingTicks = 0;
            fly(level, aircraft, state);
            if (state.mode != ApMode.OFF && aircraft.ticksExisted % 20 == 0) {
                record(aircraft, state);
            }
            if (state.dirty || aircraft.ticksExisted % 10 == 0) {
                state.dirty = false;
                sendStatus(server, aircraft, state);
            }
            if (state.mode == ApMode.OFF && !state.autoTrim) {
                STATES.remove(entry.getKey());
            }
        }
    }

    private static void fly(ServerLevel level, EntityVehicleF_Physics aircraft, State state) {
        double pitch = pitchUp(aircraft);
        double pitchRate = Double.isNaN(state.lastPitch) ? 0 : (pitch - state.lastPitch) * 20;
        state.lastPitch = pitch;
        double verticalSpeed = Double.isNaN(state.lastY) ? 0 : (aircraft.position.y - state.lastY) * 20; // blocks/s
        state.lastY = aircraft.position.y;
        double maxTrim = EntityVehicleF_Physics.MAX_ELEVATOR_TRIM;

        if (state.mode == ApMode.OFF) {
            if (state.autoTrim) {
                double held = aircraft.elevatorInputVar.currentValue;
                setTrim(aircraft.elevatorTrimVar, Mth.clamp(aircraft.elevatorTrimVar.currentValue + held * ApConfig.AUTO_TRIM_RATE.get(), -maxTrim, maxTrim));
            }
            state.info = "";
            return;
        }

        if (state.delayTicks > 0) {
            // Delayed start: stay parked until it's time (touching the controls cancels).
            if (aircraft.lastController != null) {
                setMode(aircraft, state, ApMode.OFF);
                notifyRiders(level.getServer(), aircraft, "msg.ivautopilot.delay_cancelled");
                state.dirty = true;
                return;
            }
            Takeoff.setIf(aircraft.throttleVar, 0);
            Takeoff.setIf(aircraft.parkingBrakeVar, 1);
            int s = (state.delayTicks + 19) / 20;
            String what = state.taxi != null && state.plan == null ? "TAXI to " + state.taxi.route.target()
                    : state.plan != null ? "FLY to " + state.plan.runway.name() : "";
            state.info = String.format("%s  starts in %d:%02d", what, s / 60, s % 60);
            if (--state.delayTicks == 0) {
                Takeoff.setIf(aircraft.parkingBrakeVar, 0);
                aircraft.lastController = null;
                state.lastHeading = Double.NaN;
                notifyRiders(level.getServer(), aircraft, "msg.ivautopilot.delay_over");
            }
            return;
        }
        FlightPlan plan = state.plan;
        keepSystemsOn(aircraft, state, plan);
        updateBankLock(level, aircraft, state, plan);
        updateStallProtection(aircraft, state, plan);
        if (state.mode == ApMode.NAV && state.taxi != null) {
            taxiTick(level, aircraft, state);
            return;
        }
        if (state.mode == ApMode.NAV && plan != null && plan.phase == FlightPlan.Phase.TAKEOFF_ROLL && plan.departure != null
                && aircraft.indicatedSpeed < 8 && aircraft.lastController == null && !TrafficControl.request(level, plan.departure, aircraft.uniqueUUID, false)) {
            // Lined up but the runway is in use (an aircraft in front of us, landing or about to): wait on
            // the brakes. Checked until 8 blocks/s, so a slow start can still stop for something ahead.
            Takeoff.startEngines(aircraft);
            Takeoff.setIf(aircraft.throttleVar, 0);
            Takeoff.setIf(aircraft.brakeVar, 1);
            state.info = "LINED UP, WAITING: " + plan.departure.name() + " in use";
            return;
        }
        if (state.mode == ApMode.NAV && plan != null && !isOnGround(aircraft) && aircraft.ticksExisted % 10 == 0) {
            updateSeparation(level, aircraft, state, plan);
        }
        if (state.mode == ApMode.NAV && plan != null && isTakeoffPhase(plan.phase)) {
            String problem = Takeoff.tick(aircraft, state, plan, pitch, pitchRate, verticalSpeed);
            if (problem != null) {
                Takeoff.abort(aircraft);
                setMode(aircraft, state, ApMode.OFF);
                notifyRiders(level.getServer(), aircraft, problem);
                state.dirty = true;
            }
            return;
        }
        if (state.mode == ApMode.NAV && plan != null && Landing.isHandsOnPhase(plan.phase)) {
            String result = Landing.handsOn(level, aircraft, state, plan, pitch, pitchRate, verticalSpeed);
            if ("exit".equals(result)) {
                double here = -Landing.distBefore(new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z), plan);
                if (!taxiToGate(level, aircraft, state, plan, plan.exitAlong - here - 1)) {
                    plan.exitAlong = Double.NaN; // no way off after all: roll on to the end
                }
                return;
            }
            if ("landed".equals(result) && taxiToGate(level, aircraft, state, plan, 0)) {
                return;
            }
            if (result != null) {
                setMode(aircraft, state, ApMode.OFF);
                notifyRiders(level.getServer(), aircraft, result.equals("landed")
                        ? Component.translatable("msg.ivautopilot.landed", plan.runway.name())
                        : Component.translatable(result));
                state.dirty = true;
            }
            return;
        }

        double targetPitch = state.targetPitch;
        if (state.mode == ApMode.ALTITUDE) {
            targetPitch = altitudeToPitch(state, state.targetAltitude, aircraft.position.y, verticalSpeed);
            state.info = "";
        } else if (state.mode == ApMode.NAV && plan != null && Landing.isGuidedPhase(plan.phase)) {
            Landing.Guidance guidance = Landing.guide(level, aircraft, state, plan, pitch, verticalSpeed);
            if (Landing.isHandsOnPhase(plan.phase)) {
                return; // just started the flare; the elevator takes over next tick
            }
            targetPitch = guidance.pitch();
            holdBank(aircraft, state, guidance.bank());
            state.targetSpeed = guidance.speed();
            if (Double.isNaN(guidance.throttle())) {
                holdSpeed(aircraft, state, guidance.speed());
            } else {
                Takeoff.setIf(aircraft.throttleVar, guidance.throttle());
                state.throttleIntegral = guidance.throttle();
            }
            state.targetAltitude = guidance.altitude();
        } else if (state.mode == ApMode.NAV && state.plan != null) {
            NavCommand nav = navigate(level, aircraft, state, state.plan);
            targetPitch = altitudeToPitch(state, nav.altitude, aircraft.position.y, verticalSpeed);
            holdBank(aircraft, state, nav.bank);
            holdSpeed(aircraft, state, state.targetSpeed);
            state.targetAltitude = nav.altitude;
        }

        if (state.stallProtect) {
            // Lower the nose until the angle of attack is back under the limit, with full power.
            double limit = ApConfig.AOA_LIMIT.get();
            targetPitch = Math.min(targetPitch, !Double.isNaN(state.aoa) && state.aoa > limit - 2
                    ? pitch - 2 * (state.aoa - limit) - 1   // angle of attack too high: lower the nose by the excess
                    : Math.min(pitch - 0.5, 0));            // too slow: trade height for speed
            Takeoff.setIf(aircraft.throttleVar, AEntityVehicleE_Powered.MAX_THROTTLE);
            state.throttleIntegral = AEntityVehicleE_Powered.MAX_THROTTLE;
            state.speedCommand = aircraft.indicatedSpeed;
            state.info = (state.info.isEmpty() ? "" : state.info + "  ") + "STALL PROTECTION";
        }

        // Pitch hold through elevator trim (PID; the integral starts at the trim we engaged with).
        state.commandPitch = targetPitch;
        double error = targetPitch - pitch;
        state.pitchIntegral = Mth.clamp(state.pitchIntegral + ApConfig.PITCH_KI.get() * error, -maxTrim, maxTrim);
        double command = Mth.clamp(ApConfig.PITCH_KP.get() * error + state.pitchIntegral - ApConfig.PITCH_KD.get() * pitchRate, -maxTrim, maxTrim);
        double trim = aircraft.elevatorTrimVar.currentValue;
        double maxStep = ApConfig.MAX_TRIM_RATE.get();
        setTrim(aircraft.elevatorTrimVar, trim + Mth.clamp(command - trim, -maxStep, maxStep));
    }

    /**
     * Nobody at the controls is fine: when the pilot gets up, MTS (client option "auto-start
     * engines", on by default) switches every magneto off and sets the parking brake. While the
     * autopilot flies, switch them straight back: an engine still turning above its start RPM
     * relights by itself, otherwise the starter spins it up.
     */
    private static void keepSystemsOn(EntityVehicleF_Physics aircraft, State state, @Nullable FlightPlan plan) {
        boolean flying = !isOnGround(aircraft);
        boolean navigating = state.mode == ApMode.NAV && plan != null;
        if (!flying && !navigating) {
            return; // parked with pitch/altitude hold on: leave the aircraft alone
        }
        boolean takeoffRoll = navigating && plan.phase == FlightPlan.Phase.TAKEOFF_ROLL;
        if (!takeoffRoll) { // the takeoff roll starts the engines itself
            for (minecrafttransportsimulator.entities.instances.PartEngine engine : new ArrayList<>(aircraft.engines)) {
                Takeoff.setIf(engine.magnetoVar, 1);
                boolean needsStarter = !engine.running && engine.rpm < engine.definition.engine.startRPM;
                Takeoff.setIf(engine.electricStarterVar, needsStarter ? 1 : 0);
            }
        }
        if (aircraft.parkingBrakeVar.isActive) {
            Takeoff.setIf(aircraft.parkingBrakeVar, 0);
        }
    }

    /** Flight data recorder: one line a second in the game log, for diagnosing a bad flight afterwards. */
    private static void record(EntityVehicleF_Physics aircraft, State state) {
        FlightPlan plan = state.plan;
        IvAutopilot.LOGGER.info(String.format(java.util.Locale.ROOT,
                "[FDR] %s %s %s | xz %.0f %.0f | spd %.1f tgt %.1f stall %.1f | y %.1f tgtY %.1f vs %.1f | pitch %.1f tgtP %.1f aoa %.1f | bank %.1f | "
                        + "elevTrim %.1f elevIn %.1f ailTrim %.1f rudTrim %.1f rollGain %.2f | thr %.2f flaps %.0f gear %s | protect %s lock %s | %s",
                aircraft.uniqueUUID.toString().substring(0, 4), state.mode, plan == null ? "-" : plan.phase,
                aircraft.position.x, aircraft.position.z, aircraft.indicatedSpeed, state.targetSpeed,
                Takeoff.liftSpeed(aircraft, aircraft.flapActualAngleVar.currentValue, 15, 1, true) * (plan == null ? 1 : plan.stallScale),
                aircraft.position.y, state.targetAltitude, (aircraft.position.y - aircraft.prevPosition.y) * 20,
                pitchUp(aircraft), state.commandPitch, state.aoa, bankOf(aircraft),
                aircraft.elevatorTrimVar.currentValue, aircraft.elevatorInputVar.currentValue, aircraft.aileronTrimVar.currentValue,
                aircraft.rudderTrimVar.currentValue, state.rollGain, aircraft.throttleVar.currentValue, aircraft.flapActualAngleVar.currentValue,
                aircraft.retractGearVar.isActive ? "up" : "down", state.stallProtect, state.bankLocked, state.info));
    }

    /**
     * Angle of attack from the aircraft's motion in its own frame (MTS local +z forward, +y up),
     * filtered. Protection switches on above the limit and off 2 degrees below it. Each time it trips,
     * the stall speed the lift model predicts is corrected toward what the aircraft really does, so
     * circuit and approach speeds come up for the rest of the flight.
     */
    private static void updateStallProtection(EntityVehicleF_Physics aircraft, State state, @Nullable FlightPlan plan) {
        double vx = aircraft.position.x - aircraft.prevPosition.x, vy = aircraft.position.y - aircraft.prevPosition.y,
                vz = aircraft.position.z - aircraft.prevPosition.z;
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz) * 20;
        if (isOnGround(aircraft) || speed < 8) {
            state.stallProtect = false;
            state.aoa = Double.NaN;
            return;
        }
        Point3D local = aircraft.orientation.reOrigin(new Point3D(vx, vy, vz));
        double aoa = Math.toDegrees(Math.atan2(-local.y, Math.max(1.0E-4, local.z)));
        state.aoa = Double.isNaN(state.aoa) ? aoa : state.aoa * 0.7 + aoa * 0.3;
        double limit = ApConfig.AOA_LIMIT.get();
        boolean was = state.stallProtect;
        // Also by speed, as a backstop: below 1.1 x the stall speed for the current flaps and bank.
        double bankNow = Math.min(60, Math.abs(bankOf(aircraft)));
        double stallNow = Takeoff.liftSpeed(aircraft, aircraft.flapActualAngleVar.currentValue, 15, 1, true)
                * (plan != null ? plan.stallScale : 1) / Math.sqrt(Math.cos(Math.toRadians(bankNow)));
        boolean slow = aircraft.indicatedSpeed < stallNow * (was ? 1.2 : 1.1);
        state.stallProtect = state.aoa > limit || (was && state.aoa > limit - 2) || slow;
        if (state.stallProtect && !was && plan != null) {
            // Lift ~ v^2 sin(pi/2 aoa/15): the stall speed this implies vs. the model's, in this bank.
            double bank = Math.min(60, Math.abs(bankOf(aircraft)));
            double model = Takeoff.liftSpeed(aircraft, aircraft.flapActualAngleVar.currentValue, 15, 1, true) / Math.sqrt(Math.cos(Math.toRadians(bank)));
            double real = aircraft.indicatedSpeed * Math.sqrt(Math.sin(Math.PI / 2 * Math.min(15, state.aoa) / 15));
            if (model > 0 && real > 0) {
                plan.stallScale = Mth.clamp(Math.max(plan.stallScale, real / model * 1.05), 1, 2.5);
            }
            IvAutopilot.LOGGER.info("Stall protection at {} km/h, aoa {}; stall speed scale now {}", Math.round(aircraft.indicatedSpeed * 3.6),
                    Math.round(state.aoa), String.format("%.2f", plan.stallScale));
        }
    }

    /**
     * No banking (1) after a takeoff until past the far end of the departure runway (holding runway
     * heading), and (2) while departing an airport, until out of its area (or above its ceiling).
     * Arriving, circuit, approach, landing and go-around are never restricted by an airport, so an
     * airport can contain its runways' whole approach without getting in the way. Pitch/altitude
     * hold (no flight plan) keeps the old rule: wings level anywhere in an airport.
     */
    private static void updateBankLock(ServerLevel level, EntityVehicleF_Physics aircraft, State state, @Nullable FlightPlan plan) {
        state.bankLocked = false;
        state.lockHeading = Double.NaN;
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        if (plan != null && plan.departure != null && !plan.clearedDeparture) {
            Vec3 dir = Runway.heading(plan.departureStart, plan.departureEnd);
            double along = (pos.x - plan.departureStart.x) * dir.x + (pos.z - plan.departureStart.z) * dir.z;
            double length = FlightPlan.horizontal(plan.departureStart, plan.departureEnd);
            if (along > length || FlightPlan.horizontal(pos, plan.departureStart) > length + 1000) {
                plan.clearedDeparture = true;
            } else {
                state.bankLocked = true;
                state.lockHeading = yawOf(dir);
                state.lockReason = "WINGS LEVEL to runway end";
                return;
            }
        }
        Airport airport = AirportRegistry.get(level).containing(level.dimension(), pos.x, pos.y, pos.z, ApConfig.AIRPORT_CEILING.get());
        boolean departing = plan == null || plan.departure != null && plan.departureStart != null
                && (isTakeoffPhase(plan.phase) || plan.phase == FlightPlan.Phase.ENROUTE)
                && airport != null && airport.containsXZ(plan.departureStart.x, plan.departureStart.z);
        if (airport != null && departing) {
            state.bankLocked = true;
            state.lockReason = "WINGS LEVEL (" + airport.name() + ")";
        }
    }

    static boolean isTakeoffPhase(FlightPlan.Phase phase) {
        return phase == FlightPlan.Phase.TAKEOFF_ROLL || phase == FlightPlan.Phase.ROTATE || phase == FlightPlan.Phase.CLIMB_OUT;
    }

    static void notifyRiders(MinecraftServer server, EntityVehicleF_Physics aircraft, String key) {
        notifyRiders(server, aircraft, Component.translatable(key));
    }

    static void notifyRiders(MinecraftServer server, EntityVehicleF_Physics aircraft, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getVehicle() instanceof BuilderEntityLinkedSeat seatBuilder && seatBuilder.entity instanceof PartSeat seat
                    && seat.vehicleOn == aircraft) {
                player.displayClientMessage(message, false);
            }
        }
    }

    /** Target pitch to reach/hold an altitude, with the climb/descent rate limited. */
    /**
     * Target pitch to reach/hold an altitude: altitude error -> climb/descent rate (limited), then a
     * PI vertical-speed loop -> pitch. The integral finds the pitch the aircraft needs for level flight
     * at its current speed, so there is no steady altitude error (the old P-only loop could sit 15
     * blocks low at slow speeds).
     */
    static double altitudeToPitch(State state, double targetAltitude, double altitude, double verticalSpeed) {
        double maxPitch = ApConfig.MAX_PITCH.get();
        double maxVs = ApConfig.MAX_VERTICAL_SPEED.get();
        double vsTarget = Mth.clamp(ApConfig.ALT_KP.get() * (targetAltitude - altitude), -maxVs, maxVs);
        return Mth.clamp(Landing.verticalSpeedPitch(state, vsTarget, verticalSpeed), -maxPitch, maxPitch);
    }

    // ------------------------------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------------------------------

    private record NavCommand(double bank, double altitude) {
    }

    private static NavCommand navigate(ServerLevel level, EntityVehicleF_Physics aircraft, State state, FlightPlan plan) {
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);

        // (Re)plan the route in the background: first time, every 10 s, and whenever the terrain guard trips.
        if (plan.pendingPlan == null && plan.phase == FlightPlan.Phase.ENROUTE && (plan.route == null || --plan.replanTimer <= 0)) {
            Terrain terrain = new Terrain(level);
            Vec3 goal = plan.entry;
            double dist = FlightPlan.horizontal(pos, goal);
            if (dist > 8000) {
                // Long trips: plan the next 8 km toward the destination; later replans extend it.
                Vec3 dir = new Vec3(goal.x - pos.x, 0, goal.z - pos.z).normalize();
                goal = new Vec3(pos.x + dir.x * 8000, goal.y, pos.z + dir.z * 8000);
            }
            Vec3 start = pos, finalGoal = goal;
            double cruise = Math.max(plan.cruiseAltitude, plan.guardTicks > 0 ? plan.guardAltitude : 0);
            double clearance = ApConfig.TERRAIN_CLEARANCE.get();
            plan.pendingPlanStart = pos;
            plan.pendingPlan = CompletableFuture.supplyAsync(() -> RoutePlanner.plan(terrain, start, finalGoal, cruise, clearance), PLANNER);
        }
        if (plan.pendingPlan != null && plan.pendingPlan.isDone()) {
            try {
                plan.route = plan.pendingPlan.join();
                plan.waypointIndex = 0;
                plan.legStart = pos;
            } catch (Exception e) {
                IvAutopilot.LOGGER.warn("Route planning failed", e);
            }
            plan.pendingPlan = null;
            plan.replanTimer = 200;
        }

        terrainGuard(level, aircraft, plan, pos);

        // Close to the destination (and clear of the departure runway): fly a planned circuit that arrives lined up.
        if (plan.phase == FlightPlan.Phase.ENROUTE && (plan.departure == null || plan.clearedDeparture)
                && FlightPlan.horizontal(pos, plan.entry) < ApConfig.PATTERN_DISTANCE.get()) {
            Landing.startPattern(aircraft, plan);
            return new NavCommand(0, Math.max(pos.y, plan.entry.y));
        }

        double targetAltitude;
        Vec3 aim;
        if (plan.phase != FlightPlan.Phase.HOLDING && plan.route == null) {
            // Route still being computed (well under a second): head straight for the destination meanwhile.
            aim = plan.entry;
            targetAltitude = Math.max(pos.y, plan.cruiseAltitude);
            state.info = "PLANNING ROUTE";
        } else if (plan.phase == FlightPlan.Phase.HOLDING) {
            // Hold: fly a circle around the entry point.
            Vec3 centre = plan.route == null && !plan.waitingForRunway ? (plan.legStart != null ? plan.legStart : pos) : plan.entry;
            if (plan.legStart == null) {
                plan.legStart = pos;
            }
            double radius = ApConfig.HOLD_RADIUS.get();
            Vec3 toCentre = new Vec3(centre.x - pos.x, 0, centre.z - pos.z);
            double d = toCentre.length();
            // Clockwise circle: aim 90 degrees right of the centre bearing, corrected toward the circle.
            double correction = Mth.clamp((d - radius) / radius, -1, 1) * 60;
            double bearing = yawOf(toCentre) - 90 + correction;
            aim = pos.add(new Vec3(-Math.sin(Math.toRadians(bearing)), 0, Math.cos(Math.toRadians(bearing))).scale(100));
            targetAltitude = plan.route == null && !plan.waitingForRunway ? Math.max(pos.y, plan.cruiseAltitude) : plan.entry.y;
            state.info = plan.route == null && !plan.waitingForRunway ? "PLANNING ROUTE" : "HOLDING at " + plan.runway.name() + " approach";
            if (plan.waitingForRunway) {
                // Waiting for the runway: stacked by queue place; once it's our turn, fly the circuit in.
                plan.queuePosition = TrafficControl.arrivalPosition(level, plan.runway, aircraft.uniqueUUID);
                targetAltitude += 30 * plan.queuePosition;
                state.info = String.format("HOLDING for %s  number %d to land%s", plan.runway.name(), plan.queuePosition + 1,
                        plan.queuePosition == 0 ? ", runway in use" : "");
                if (aircraft.ticksExisted % 20 == 0 && TrafficControl.available(level, plan.runway, aircraft.uniqueUUID, true)) {
                    plan.waitingForRunway = false;
                    Landing.startPattern(aircraft, plan);
                    return new NavCommand(0, targetAltitude);
                }
            }
        } else {
            List<RoutePlanner.Waypoint> route = plan.route;
            RoutePlanner.Waypoint wp = route.get(plan.waypointIndex);
            Vec3 target = new Vec3(wp.x(), 0, wp.z());
            Vec3 legStart = plan.legStart != null ? plan.legStart : pos;
            Vec3 leg = new Vec3(target.x - legStart.x, 0, target.z - legStart.z);
            double legLength = Math.max(1, leg.length());
            Vec3 legDir = leg.scale(1 / legLength);
            double along = (pos.x - legStart.x) * legDir.x + (pos.z - legStart.z) * legDir.z;
            double remaining = FlightPlan.horizontal(pos, target);
            if (remaining < ApConfig.CAPTURE_RADIUS.get() || along > legLength) {
                // Waypoint reached.
                plan.legStart = target;
                if (++plan.waypointIndex >= route.size()) {
                    boolean atEntry = FlightPlan.horizontal(target, plan.entry) < 1;
                    plan.waypointIndex = route.size() - 1;
                    if (atEntry) {
                        plan.setPhase(FlightPlan.Phase.APPROACH); // stage 3: intercept and land
                    } else {
                        plan.replanTimer = 0; // partial route (long trip): plan the next stretch
                        plan.route = null;
                    }
                }
                return navigate(level, aircraft, state, plan);
            }
            // Aim at a point a fixed distance ahead along the leg: converges onto the line smoothly.
            double ahead = Math.min(legLength, Math.max(0, along) + ApConfig.LOOKAHEAD.get());
            aim = legStart.add(legDir.scale(ahead));
            targetAltitude = wp.altitude();
            boolean lastLeg = plan.waypointIndex == route.size() - 1 && FlightPlan.horizontal(target, plan.entry) < 1;
            if (lastLeg) {
                // Descend toward the entry altitude on a fixed gradient, never below this leg's safe altitude.
                double descentAlt = plan.entry.y + remaining * ApConfig.DESCENT_GRADIENT.get();
                targetAltitude = Math.min(wp.altitude(), Math.max(descentAlt, plan.entry.y));
            }
            state.info = String.format("TO %s  wp %d/%d  %.1f km  dest %.1f km", plan.runway.name(), plan.waypointIndex + 1,
                    route.size(), remaining / 1000, FlightPlan.horizontal(pos, plan.entry) / 1000);
        }
        if (state.separation > 0) {
            targetAltitude += state.separation;
            state.info += String.format("  TRAFFIC +%.0f", state.separation);
        }
        if (plan.guardTicks > 0) {
            targetAltitude = Math.max(targetAltitude, plan.guardAltitude);
            state.info += "  TERRAIN";
        }

        double headingError = Mth.wrapDegrees(yawOf(aim.subtract(pos)) - headingOf(aircraft));
        double maxBank = ApConfig.MAX_BANK.get();
        double bank = Mth.clamp(headingError * ApConfig.HEADING_GAIN.get(), -maxBank, maxBank);
        return new NavCommand(bank, targetAltitude);
    }

    /**
     * Terrain guard: look at the REAL ground (loaded chunks) under where we'll be over the next ten
     * seconds. If we'd come within the guard clearance, climb immediately and re-plan.
     */
    static void terrainGuard(ServerLevel level, EntityVehicleF_Physics aircraft, FlightPlan plan, Vec3 pos) {
        if (plan.guardTicks > 0) {
            plan.guardTicks--;
        }
        if (aircraft.ticksExisted % 5 != 0) {
            return;
        }
        Vec3 velocity = new Vec3(aircraft.position.x - aircraft.prevPosition.x, aircraft.position.y - aircraft.prevPosition.y,
                aircraft.position.z - aircraft.prevPosition.z).scale(20); // blocks per second
        Terrain terrain = new Terrain(level);
        double need = Double.NEGATIVE_INFINITY;
        for (int t = 0; t <= 10; t++) {
            Vec3 p = pos.add(velocity.x * t, 0, velocity.z * t);
            int ground = terrain.actual((int) Math.floor(p.x), (int) Math.floor(p.z));
            if (ground != Integer.MIN_VALUE) {
                double projected = pos.y + Math.min(0, velocity.y) * t;
                double required = ground + ApConfig.GUARD_CLEARANCE.get();
                if (projected < required) {
                    need = Math.max(need, required + 15);
                }
            }
        }
        if (need > Double.NEGATIVE_INFINITY) {
            plan.guardAltitude = Math.max(plan.guardTicks > 0 ? plan.guardAltitude : 0, need);
            plan.guardTicks = 200;
            if (plan.pendingPlan == null) {
                plan.replanTimer = 0;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Bank and speed
    // ------------------------------------------------------------------------------------------

    /** Bank angle hold through aileron trim. Positive bank = right wing down = turning right. */
    static void holdBank(EntityVehicleF_Physics aircraft, State state, double targetBank) {
        if (state.stallProtect) {
            targetBank = Mth.clamp(targetBank, -10, 10); // less bank = less load on the wing
        }
        double bank = bankOf(aircraft);
        double heading = headingOf(aircraft);
        double yawRate = Double.isNaN(state.lastYawHeading) ? 0 : Mth.wrapDegrees(heading - state.lastYawHeading) * 20;
        state.lastYawHeading = heading;
        double maxRudderTrim = EntityVehicleF_Physics.MAX_RUDDER_TRIM;
        double rudderTrim = aircraft.rudderTrimVar.currentValue;
        if (state.bankLocked) {
            // Wings level; turn flat with rudder trim toward the locked heading, or toward where the bank would have turned us.
            double headingError = !Double.isNaN(state.lockHeading) ? Mth.wrapDegrees(state.lockHeading - heading)
                    : targetBank / ApConfig.HEADING_GAIN.get();
            if (Math.abs(bank) < 3 && Math.abs(rudderTrim) > 2 && Math.abs(yawRate) > 0.5) {
                state.aeroRudderSign = Math.signum(yawRate * rudderTrim); // learn which way rudder yaws us
            }
            double command = state.aeroRudderSign * (ApConfig.NO_BANK_RUDDER_GAIN.get() * headingError - 0.3 * yawRate);
            command = Mth.clamp(command, -maxRudderTrim, maxRudderTrim);
            setTrim(aircraft.rudderTrimVar, rudderTrim + Mth.clamp(command - rudderTrim, -ApConfig.MAX_TRIM_RATE.get(), ApConfig.MAX_TRIM_RATE.get()));
            targetBank = 0;
        } else if (rudderTrim != 0) {
            setTrim(aircraft.rudderTrimVar, Math.abs(rudderTrim) < 0.2 ? 0 : rudderTrim - Math.signum(rudderTrim) * 0.2);
        }
        // Learn which way aileron trim rolls us, from MTS's roll angle vs. the geometric bank.
        double mtsRoll = new RotationMatrix().set(aircraft.orientation).convertToAngles().z;
        if (Math.abs(bank) > 2 && Math.abs(mtsRoll) > 2) {
            state.aileronSign = Math.signum(mtsRoll * bank);
        }
        double rollRate = Double.isNaN(state.lastBank) ? 0 : (bank - state.lastBank) * 20;
        state.lastBank = bank;
        double maxTrim = EntityVehicleF_Physics.MAX_AILERON_TRIM;
        double maxBank = Math.max(ApConfig.MAX_BANK.get(), ApConfig.PATTERN_BANK.get() + 5);
        targetBank = Mth.clamp(targetBank, -maxBank, maxBank);
        double error = targetBank - bank;
        adaptRollGain(state, error, bank, targetBank);

        // Aileron deflection sets the ROLL RATE, not the bank angle, so: bank error -> wanted roll rate
        // (limited), and roll-rate error -> aileron trim, scaled by this aircraft's learned gain. A small
        // integral on the rate error only trims out asymmetry. (Integrating the bank error, as before,
        // double-integrates and makes high-authority aircraft like the 727 swing from bank to bank.)
        // Tuned in a roll simulation over a 32x range of aileron effectiveness (config values act as multipliers).
        double gain = state.rollGain;
        double kBank = ApConfig.BANK_KP.get() / 0.3 * 1.0;   // wanted roll rate (deg/s) per degree of bank error
        double kRate = ApConfig.BANK_KD.get() / 0.15 * 0.5;  // aileron trim per deg/s of roll-rate error
        double kInt = ApConfig.BANK_KI.get() / 0.002 * 0.04; // ... integral, per tick
        double wantedRate = Mth.clamp(kBank * error, -15, 15);
        double rateError = wantedRate - rollRate;
        double raw = state.aileronSign * gain * kRate * rateError + state.bankIntegral;
        boolean saturated = (raw >= maxTrim && state.aileronSign * rateError > 0) || (raw <= -maxTrim && state.aileronSign * rateError < 0);
        if (!saturated) { // anti-windup: don't integrate while the ailerons can't do more anyway
            state.bankIntegral = Mth.clamp(state.bankIntegral + state.aileronSign * kInt * gain * rateError, -maxTrim, maxTrim);
        }
        double command = state.aileronSign * gain * kRate * rateError + state.bankIntegral;
        if (Math.abs(bank) > maxBank + 12) {
            command = -Math.signum(bank) * state.aileronSign * maxTrim; // way past the limit: roll back hard
        }
        command = Mth.clamp(command, -maxTrim, maxTrim);
        double trim = aircraft.aileronTrimVar.currentValue;
        double maxStep = ApConfig.MAX_TRIM_RATE.get();
        setTrim(aircraft.aileronTrimVar, trim + Mth.clamp(command - trim, -maxStep, maxStep));
    }

    /**
     * Lower the roll gain when this aircraft overshoots its target bank or the bank error keeps
     * changing sign (oscillation); let it creep back up while the bank is steady.
     */
    private static void adaptRollGain(State state, double error, double bank, double targetBank) {
        if (state.rollGainCooldown > 0) {
            state.rollGainCooldown--;
        }
        if (++state.flipWindow > 120) { // 6 s window
            state.flipWindow = 0;
            state.bankErrorFlips = 0;
        }
        if (Math.signum(error) != Math.signum(state.lastBankError) && Math.abs(state.lastBankError) > 4) {
            state.bankErrorFlips++;
        }
        state.lastBankError = error;
        // Overshoot = carried past the target bank by more than 6 degrees (target changes alone don't count).
        boolean overshoot = Math.abs(bank) > Math.abs(targetBank) + 6 && Math.abs(error) > 6
                && (targetBank == 0 || Math.signum(bank) == Math.signum(targetBank));
        if (overshoot && state.rollGainCooldown == 0) {
            state.rollGain = Math.max(0.15, state.rollGain * 0.7);
            state.rollGainCooldown = 40;
            state.bankErrorFlips = 0;
            IvAutopilot.LOGGER.info("Bank control: overshoot/oscillation, roll gain now {}", String.format("%.2f", state.rollGain));
        } else if (Math.abs(error) < 4) {
            state.rollGain = Math.min(1.5, state.rollGain * 1.0005); // slow recovery (x2 in ~70 s)
        }
    }

    /** Autothrottle: PI on indicated airspeed. */
    private static void holdSpeed(EntityVehicleF_Physics aircraft, State state, double targetSpeed) {
        if (Double.isNaN(state.throttleIntegral)) {
            state.throttleIntegral = aircraft.throttleVar.currentValue;
        }
        // Slow down gradually (1 block/s per second) so the speed doesn't undershoot; speed up at once.
        if (Double.isNaN(state.speedCommand)) {
            state.speedCommand = Math.max(targetSpeed, aircraft.indicatedSpeed);
        }
        if (targetSpeed > state.speedCommand) {
            state.speedCommand = targetSpeed;
        } else {
            state.speedCommand = Math.max(targetSpeed, state.speedCommand - 0.05);
        }
        double error = state.speedCommand - aircraft.indicatedSpeed;
        state.throttleIntegral = Mth.clamp(state.throttleIntegral + ApConfig.THROTTLE_KI.get() * error, 0, AEntityVehicleE_Powered.MAX_THROTTLE);
        double throttle = Mth.clamp(state.throttleIntegral + ApConfig.THROTTLE_KP.get() * error, 0, AEntityVehicleE_Powered.MAX_THROTTLE);
        if (error > Math.max(6, 0.15 * state.speedCommand)) {
            // Far below the speed we want: more power now, don't wait for the integral.
            throttle = Math.max(throttle, Math.min(1, 0.6 + 0.02 * error));
            state.throttleIntegral = Math.max(state.throttleIntegral, 0.4);
        }
        if (Math.abs(throttle - aircraft.throttleVar.currentValue) > 0.005) {
            aircraft.throttleVar.setTo(throttle, true);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------------------------------

    /** Pitch angle, positive nose up, in degrees. Computed on a copy so the live matrix isn't touched. */
    static double pitchUp(EntityVehicleF_Physics aircraft) {
        return -new RotationMatrix().set(aircraft.orientation).convertToAngles().x;
    }

    /** Bank angle, positive = right wing down. MTS local +x is the aircraft's LEFT side. */
    static double bankOf(EntityVehicleF_Physics aircraft) {
        Point3D left = aircraft.orientation.rotate(new Point3D(1, 0, 0));
        return Math.toDegrees(Math.asin(Mth.clamp(left.y, -1, 1)));
    }

    /** Heading as a Minecraft yaw (0 = +Z/south, 90 = -X/west; increasing = turning right). */
    static double headingOf(EntityVehicleF_Physics aircraft) {
        Point3D forward = aircraft.orientation.rotate(new Point3D(0, 0, 1));
        return Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    /**
     * True if any landing gear touches the ground. Note MTS's isAnythingOnGround() is misnamed:
     * it returns true when NOTHING is on the ground (i.e. airborne), so we invert it.
     */
    static boolean isOnGround(EntityVehicleF_Physics aircraft) {
        return aircraft.groundDeviceCollective != null && !aircraft.groundDeviceCollective.isAnythingOnGround();
    }

    static double yawOf(Vec3 direction) {
        return Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    private static void setTrim(minecrafttransportsimulator.baseclasses.ComputedVariable variable, double value) {
        if (Math.abs(value - variable.currentValue) > 1.0E-3) {
            variable.setTo(value, true);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Status to riders
    // ------------------------------------------------------------------------------------------

    /**
     * Estimated seconds to touchdown: the distance still to fly (rest of the route or circuit to the
     * approach entry, then the final approach) at the current ground speed, with the final approach
     * at approach speed. NaN when there's nothing sensible to estimate (holding, rolling out, ...).
     */
    private static double secondsToLanding(EntityVehicleF_Physics aircraft, State state) {
        FlightPlan plan = state.plan;
        if (plan == null) {
            return Double.NaN;
        }
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        double ground = new Vec3(aircraft.position.x - aircraft.prevPosition.x, 0, aircraft.position.z - aircraft.prevPosition.z).length() * 20;
        double approachSpeed = Math.max(5, Landing.approachSpeed(aircraft, plan));
        double finalLength = FlightPlan.horizontal(plan.entry, plan.threshold);
        double toEntry; // distance to the approach entry
        switch (plan.phase) {
            case TAKEOFF_ROLL, ROTATE, CLIMB_OUT -> {
                ground = Math.max(ground, state.targetSpeed); // still accelerating: assume cruise speed
                toEntry = FlightPlan.horizontal(pos, plan.entry);
            }
            case ENROUTE -> {
                List<RoutePlanner.Waypoint> route = plan.route;
                if (route == null || route.isEmpty()) {
                    toEntry = FlightPlan.horizontal(pos, plan.entry);
                } else {
                    int i = Math.min(plan.waypointIndex, route.size() - 1);
                    toEntry = FlightPlan.horizontal(pos, new Vec3(route.get(i).x(), 0, route.get(i).z()));
                    for (int k = i; k < route.size() - 1; k++) {
                        toEntry += FlightPlan.horizontal(new Vec3(route.get(k).x(), 0, route.get(k).z()), new Vec3(route.get(k + 1).x(), 0, route.get(k + 1).z()));
                    }
                    Vec3 last = new Vec3(route.get(route.size() - 1).x(), 0, route.get(route.size() - 1).z());
                    toEntry += FlightPlan.horizontal(last, plan.entry); // partial routes on long trips
                }
            }
            case PATTERN -> toEntry = plan.pattern == null ? FlightPlan.horizontal(pos, plan.entry)
                    : Math.max(0, plan.pattern.targetIndex - plan.pattern.nearest) * ivautopilot.nav.Pattern.STEP;
            case APPROACH, FINAL, FLARE -> {
                return Math.max(0, Landing.distBefore(pos, plan)) / Math.max(5, Math.min(ground > 5 ? ground : approachSpeed, approachSpeed * 1.5));
            }
            default -> {
                return Double.NaN;
            }
        }
        return toEntry / Math.max(10, ground) + finalLength / approachSpeed;
    }

    /** HUD line for NAV: destination runway, landing heading and length, and where we came from. */
    private static String runwayLine(State state) {
        FlightPlan plan = state.plan;
        if (state.mode != ApMode.NAV || plan == null) {
            return "";
        }
        double dx = plan.farEnd.x - plan.threshold.x, dz = plan.farEnd.z - plan.threshold.z;
        int heading = (int) Math.round(Math.toDegrees(Math.atan2(dx, -dz)));
        heading = ((heading % 360) + 360) % 360;
        String line = String.format("RWY %s  heading %03d  %d blocks", plan.runway.name(), heading, Math.round(plan.runway.length()));
        if (plan.departure != null) {
            line = "FROM " + plan.departure.name() + "  TO " + line;
        }
        return line;
    }

    private static void sendStatus(MinecraftServer server, EntityVehicleF_Physics aircraft, State state) {
        String info = state.bankLocked && state.mode != ApMode.OFF && !state.info.isEmpty() ? state.info + "  " + state.lockReason : state.info;
        double eta = state.mode == ApMode.NAV ? secondsToLanding(aircraft, state) : Double.NaN;
        if (!Double.isNaN(eta) && !info.isEmpty()) {
            int s = (int) Math.round(eta);
            info += String.format("  ETA %d:%02d", s / 60, s % 60);
        }
        ApNetwork.Status status = new ApNetwork.Status(aircraft.uniqueUUID, state.mode.ordinal(), state.autoTrim,
                (float) state.targetPitch, (float) state.targetAltitude, (float) state.targetSpeed, info, runwayLine(state));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == state.dimension && isAboard(player, aircraft)) {
                ApNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), status);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Traffic (see TrafficControl)
    // ------------------------------------------------------------------------------------------

    @Nullable
    static State stateOf(UUID id) {
        return STATES.get(id);
    }

    /** Whether this aircraft still needs the runway it holds (key = TrafficControl.key). */
    static boolean stillNeedsRunway(UUID id, String key) {
        State state = STATES.get(id);
        if (state == null || state.mode != ApMode.NAV || state.dimension == null) {
            return false;
        }
        if (state.taxi != null) {
            return state.taxi.route.runway() != null && TrafficControl.key(state.dimension, state.taxi.route.runway()).equals(key);
        }
        FlightPlan plan = state.plan;
        if (plan == null) {
            return false;
        }
        if (plan.departure != null && isTakeoffPhase(plan.phase) && !plan.clearedDeparture
                && TrafficControl.key(state.dimension, plan.departure).equals(key)) {
            return true;
        }
        return TrafficControl.key(state.dimension, plan.runway).equals(key) && (plan.phase == FlightPlan.Phase.PATTERN
                || plan.phase == FlightPlan.Phase.APPROACH || plan.phase == FlightPlan.Phase.FINAL || Landing.isHandsOnPhase(plan.phase));
    }

    /** Whether this aircraft is still on its way in to land on this runway (keeps its arrival queue place). */
    static boolean isArriving(UUID id, String key) {
        State state = STATES.get(id);
        FlightPlan plan = state == null ? null : state.plan;
        return plan != null && state.mode == ApMode.NAV && state.dimension != null && state.taxi == null
                && TrafficControl.key(state.dimension, plan.runway).equals(key)
                && (Landing.isGuidedPhase(plan.phase) || plan.phase == FlightPlan.Phase.HOLDING || plan.phase == FlightPlan.Phase.ENROUTE);
    }

    /** About to land, or waiting in the hold to: departures give way. */
    static boolean arrivalImminent(UUID id) {
        State state = STATES.get(id);
        FlightPlan plan = state == null ? null : state.plan;
        if (plan == null || state.mode != ApMode.NAV) {
            return false;
        }
        return switch (plan.phase) {
            case APPROACH, FINAL -> true;
            case HOLDING -> plan.waitingForRunway;
            case PATTERN -> plan.pattern != null && (plan.pattern.targetIndex - plan.pattern.nearest) * ivautopilot.nav.Pattern.STEP < 2500;
            default -> false;
        };
    }

    /** Taxiing out to a runway, or lined up / rolling for takeoff. */
    static boolean isDeparture(UUID id) {
        State state = STATES.get(id);
        if (state == null || state.mode != ApMode.NAV) {
            return false;
        }
        return state.taxi != null ? state.taxi.route.runway() != null
                : state.plan != null && state.plan.phase == FlightPlan.Phase.TAKEOFF_ROLL;
    }

    static boolean isTaxiing(UUID id) {
        State state = STATES.get(id);
        return state != null && state.mode == ApMode.NAV && state.taxi != null;
    }

    /**
     * Air separation: if another autopilot aircraft is close (horizontally and in height) and we're the
     * one that gives way, climb 60 blocks above our planned altitude, and stay up 20 s after the
     * conflict clears. Who gives way: the one further from landing (approach and final never give way).
     */
    private static void updateSeparation(ServerLevel level, EntityVehicleF_Physics aircraft, State state, FlightPlan plan) {
        if (plan.phase != FlightPlan.Phase.ENROUTE && plan.phase != FlightPlan.Phase.PATTERN && plan.phase != FlightPlan.Phase.HOLDING) {
            state.separation = 0;
            state.separationTicks = 0;
            return;
        }
        boolean conflict = false;
        for (Map.Entry<UUID, State> entry : STATES.entrySet()) {
            State other = entry.getValue();
            if (other == state || other.mode != ApMode.NAV || other.plan == null || other.taxi != null || other.dimension != level.dimension()) {
                continue;
            }
            EntityVehicleF_Physics o = find(level, entry.getKey());
            if (o == null || isOnGround(o)) {
                continue;
            }
            double dx = o.position.x - aircraft.position.x, dz = o.position.z - aircraft.position.z;
            double dy = Math.abs(o.position.y - aircraft.position.y);
            if (dx * dx + dz * dz > 300 * 300 || dy > 60 + state.separation) {
                continue;
            }
            if (givesWay(plan, other.plan, aircraft.uniqueUUID, entry.getKey())) {
                conflict = true;
                break;
            }
        }
        if (conflict) {
            state.separation = 60;
            state.separationTicks = 400;
        } else if (state.separationTicks > 0) {
            state.separationTicks -= 10;
        } else {
            state.separation = 0;
        }
    }

    private static boolean givesWay(FlightPlan mine, FlightPlan theirs, UUID me, UUID them) {
        int a = landingRank(mine.phase), b = landingRank(theirs.phase);
        if (a != b) {
            return a < b;
        }
        return me.compareTo(them) > 0;
    }

    /** Higher = closer to landing. */
    private static int landingRank(FlightPlan.Phase phase) {
        return switch (phase) {
            case APPROACH, FINAL, FLARE, ROLLOUT -> 4;
            case PATTERN -> 3;
            case HOLDING, GO_AROUND, REPOSITION -> 2;
            case ENROUTE -> 1;
            default -> 0;
        };
    }

    @Nullable
    static EntityVehicleF_Physics find(ServerLevel level, UUID id) {
        AEntityA_Base entity = WrapperWorld.getWrapperFor(level).getEntity(id);
        return entity instanceof EntityVehicleF_Physics vehicle && vehicle.isValid ? vehicle : null;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
        ChunkKeeper.clear();
    }

    private ApServer() {
    }
}
