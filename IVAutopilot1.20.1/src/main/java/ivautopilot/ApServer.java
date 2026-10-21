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
        if (isOnGround(aircraft)) {
            departure = Takeoff.findDeparture(player.serverLevel(), aircraft);
            if (departure == null) {
                return "msg.ivautopilot.not_lined_up";
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
                // Gone (destroyed, picked up, or unloaded before we could hold it).
                if (level != null) {
                    ChunkKeeper.release(level, entry.getKey());
                }
                STATES.remove(entry.getKey());
                continue;
            }
            fly(level, aircraft, state);
            if (state.mode != ApMode.OFF && aircraft.ticksExisted % 20 == 0) {
                record(aircraft, state);
            }
            if (state.dirty || aircraft.ticksExisted % 10 == 0) {
                state.dirty = false;
                sendStatus(server, aircraft, state);
            }
            if (state.mode != ApMode.OFF) {
                ChunkKeeper.keep(level, aircraft.uniqueUUID, aircraft.position.x, aircraft.position.z);
            } else if (ChunkKeeper.holds(aircraft.uniqueUUID)) {
                ChunkKeeper.release(level, aircraft.uniqueUUID);
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

        FlightPlan plan = state.plan;
        keepSystemsOn(aircraft, state, plan);
        updateBankLock(level, aircraft, state, plan);
        updateStallProtection(aircraft, state, plan);
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
                "[FDR] %s %s | spd %.1f tgt %.1f stall %.1f | y %.1f tgtY %.1f vs %.1f | pitch %.1f tgtP %.1f aoa %.1f | bank %.1f | "
                        + "elevTrim %.1f elevIn %.1f ailTrim %.1f rudTrim %.1f rollGain %.2f | thr %.2f flaps %.0f gear %s | protect %s lock %s | %s",
                state.mode, plan == null ? "-" : plan.phase,
                aircraft.indicatedSpeed, state.targetSpeed,
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
     * heading), and (2) anywhere inside an Airport Marker's zone.
     */
    private static void updateBankLock(ServerLevel level, EntityVehicleF_Physics aircraft, State state, @Nullable FlightPlan plan) {
        state.bankLocked = false;
        state.lockHeading = Double.NaN;
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        if (plan != null && plan.departure != null && !plan.clearedDeparture) {
            Vec3 dir = Runway.heading(plan.departureStart, plan.departureEnd);
            double along = (pos.x - plan.departureStart.x) * dir.x + (pos.z - plan.departureStart.z) * dir.z;
            double length = plan.departure.length();
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
        if (airport != null) {
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
            Vec3 centre = plan.route == null ? (plan.legStart != null ? plan.legStart : pos) : plan.entry;
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
            targetAltitude = plan.route == null ? Math.max(pos.y, plan.cruiseAltitude) : plan.entry.y;
            state.info = plan.route == null ? "PLANNING ROUTE" : "HOLDING at " + plan.runway.name() + " approach";
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

    @Nullable
    private static EntityVehicleF_Physics find(ServerLevel level, UUID id) {
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
