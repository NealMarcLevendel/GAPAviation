package ivautopilot;

import ivautopilot.nav.FlightPlan;
import ivautopilot.nav.Pattern;
import ivautopilot.nav.Runway;
import ivautopilot.nav.Terrain;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.entities.instances.AEntityVehicleE_Powered;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

import static ivautopilot.Takeoff.setIf;

/**
 * Stage 3: approach and landing.
 *
 *  APPROACH    gear down, approach flaps, ~1.2 x Vapp. Intercepts the extended centerline (like an
 *              ILS localizer: intercept angle proportional to the distance off the line, max 45
 *              degrees) at approach height.
 *  FINAL       established on the centerline and the glide path has come down to us: full flaps,
 *              Vapp, descend on the glide path (vertical-speed control with glide path correction).
 *              Below the decision height an unstable approach goes around.
 *  FLARE       at flare height: idle, the elevator raises the nose to cut the sink rate to a gentle
 *              touchdown. Lands long -> go around.
 *  ROLLOUT     on the ground: nose down, reversers (if any), brakes, nose-wheel steering on the
 *              centerline. Stopped -> parking brake, done.
 *  GO_AROUND   full power, climb straight ahead, gear up, then REPOSITION: fly out to a point well
 *              out on the approach side and try again (up to maxGoArounds).
 *
 * Heights here are of the WHEELS above the runway (from MTS's ground device boxes), so the glide
 * path and flare work the same for a Cessna and a 747. Vapp comes from the aircraft's own stall
 * speed with full flaps (MTS's lift model).
 */
final class Landing {
    /** What the normal pitch / bank / speed loops should hold this tick. throttle: NaN = autothrottle to speed. */
    record Guidance(double bank, double pitch, double altitude, double speed, double throttle) {
    }

    private Landing() {
    }

    static boolean isGuidedPhase(FlightPlan.Phase phase) {
        return phase == FlightPlan.Phase.PATTERN || phase == FlightPlan.Phase.APPROACH || phase == FlightPlan.Phase.FINAL
                || phase == FlightPlan.Phase.GO_AROUND || phase == FlightPlan.Phase.REPOSITION;
    }

    static boolean isHandsOnPhase(FlightPlan.Phase phase) {
        return phase == FlightPlan.Phase.FLARE || phase == FlightPlan.Phase.ROLLOUT;
    }

    // ------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------

    /** Runway surface height (the markers sit on top of it). */
    static double runwayY(FlightPlan plan) {
        return plan.threshold.y - 1;
    }

    /** Lowest point of the landing gear, or a guess from the aircraft's position. */
    static double wheelBottom(EntityVehicleF_Physics aircraft) {
        double lowest = Double.MAX_VALUE;
        if (aircraft.groundDeviceCollective != null) {
            List<BoundingBox> boxes = aircraft.groundDeviceCollective.getGroundBounds();
            for (BoundingBox box : boxes) {
                if (box != null) {
                    lowest = Math.min(lowest, box.globalCenter.y - box.heightRadius);
                }
            }
        }
        return lowest == Double.MAX_VALUE ? aircraft.position.y : lowest;
    }

    /** Wheels above the runway; over the runway itself, the real ground under us counts too. */
    static double height(ServerLevel level, EntityVehicleF_Physics aircraft, FlightPlan plan, double distBefore) {
        double ground = runwayY(plan);
        if (distBefore < 20) {
            int actual = new Terrain(level).actual((int) Math.floor(aircraft.position.x), (int) Math.floor(aircraft.position.z));
            if (actual != Integer.MIN_VALUE) {
                ground = Math.max(ground, actual);
            }
        }
        return wheelBottom(aircraft) - ground;
    }

    /** Distance before the threshold along the approach (negative once past it). */
    static double distBefore(Vec3 pos, FlightPlan plan) {
        Vec3 in = Runway.heading(plan.threshold, plan.farEnd);
        return -((pos.x - plan.threshold.x) * in.x + (pos.z - plan.threshold.z) * in.z);
    }

    /** Glide path height (wheels) at this distance before the threshold; aims a little way down the runway. */
    static double glideHeight(FlightPlan plan, double distBefore) {
        double aim = Math.min(40, plan.runway.length() * 0.2);
        return Math.max(0, (distBefore + aim) * Math.tan(Math.toRadians(ApConfig.GLIDE_SLOPE.get())));
    }

    static double approachSpeed(EntityVehicleF_Physics aircraft, FlightPlan plan) {
        double speed = Takeoff.liftSpeed(aircraft, landingFlaps(aircraft), 15, 1, true) * plan.stallScale * ApConfig.APPROACH_SPEED_FACTOR.get();
        return Math.max(speed, minimumSpeed(aircraft));
    }

    private static final java.util.Map<Object, Double> ALARM_SPEEDS = new java.util.IdentityHashMap<>();

    /**
     * Some packs sound a stall alarm below a fixed speed (GAP's A220 and 737: "airbus_stall" while
     * airborne with speed <= 35). Fly at least that + alarmMargin so the alarm stays quiet. Read from
     * the vehicle's own sound definitions: any sound with "stall" in its name gated on speed.
     */
    static double minimumSpeed(EntityVehicleF_Physics aircraft) {
        double alarm = ALARM_SPEEDS.computeIfAbsent(aircraft.definition, d -> {
            double max = 0;
            try {
                if (aircraft.definition.rendering != null && aircraft.definition.rendering.sounds != null) {
                    for (var sound : aircraft.definition.rendering.sounds) {
                        if (sound.name == null || !sound.name.toLowerCase(java.util.Locale.ROOT).contains("stall") || sound.activeAnimations == null) {
                            continue;
                        }
                        for (var animation : sound.activeAnimations) {
                            if (animation.clampMax > 0 && !animation.invert && ("speed".equals(animation.variable) || "speed_scaled".equals(animation.variable))) {
                                double limit = "speed_scaled".equals(animation.variable) ? animation.clampMax * aircraft.speedFactor : animation.clampMax;
                                max = Math.max(max, limit);
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                IvAutopilot.LOGGER.warn("Couldn't read stall alarm of {}", aircraft.definition.packID, e);
            }
            if (max > 0) {
                IvAutopilot.LOGGER.info("{} sounds a stall alarm below {} blocks/s; keeping above it", aircraft.definition.systemName, max);
            }
            return max;
        });
        return alarm > 0 ? alarm + ApConfig.ALARM_MARGIN.get() : 0;
    }

    static double landingFlaps(EntityVehicleF_Physics aircraft) {
        return Takeoff.flapNotch(aircraft, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------------------------------
    // Approach (flown through the normal trim / autothrottle loops)
    // ------------------------------------------------------------------------------------------

    static Guidance guide(ServerLevel level, EntityVehicleF_Physics aircraft, ApServer.State state, FlightPlan plan,
                          double pitch, double verticalSpeed) {
        plan.phaseTicks++;
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        Vec3 in = Runway.heading(plan.threshold, plan.farEnd);
        double inYaw = ApServer.yawOf(in);
        double heading = ApServer.headingOf(aircraft);
        double cross = Takeoff.crossTrack(pos, plan.threshold, in);
        double distBefore = distBefore(pos, plan);
        double h = height(level, aircraft, plan, distBefore);
        double approachHeight = ApConfig.APPROACH_HEIGHT.get();
        double vapp = approachSpeed(aircraft, plan);
        double groundSpeed = new Vec3(aircraft.position.x - aircraft.prevPosition.x, 0, aircraft.position.z - aircraft.prevPosition.z).length() * 20;
        double maxBank = ApConfig.MAX_BANK.get();
        boolean hasGear = aircraft.definition.motorized.gearSequenceDuration != 0;

        // Localizer: intercept angle proportional to the distance off the line.
        double intercept = Mth.clamp(ApConfig.LOCALIZER_GAIN.get() * cross, -45, 45); // left of line -> turn right
        double locBank = Mth.clamp(Mth.wrapDegrees(inYaw + intercept - heading) * ApConfig.HEADING_GAIN.get(), -maxBank, maxBank);
        boolean established = Math.abs(cross) < 15 && Math.abs(Mth.wrapDegrees(inYaw - heading)) < 15;

        switch (plan.phase) {
            case PATTERN -> {
                Pattern pattern = plan.pattern;
                int i = pattern.updateNearest(pos);
                // Traffic: take our place in the arrival queue; next in line asks for the runway in the
                // last 1.5 km of the circuit. Not ours at the end of the circuit: hold at the entry.
                boolean cleared = TrafficControl.holds(level, plan.runway, aircraft.uniqueUUID);
                if (!cleared) {
                    plan.queuePosition = TrafficControl.arrivalPosition(level, plan.runway, aircraft.uniqueUUID);
                    if (plan.queuePosition == 0 && (pattern.targetIndex - i) * Pattern.STEP < 1500) {
                        cleared = TrafficControl.request(level, plan.runway, aircraft.uniqueUUID, true);
                    }
                }
                if (i >= pattern.targetIndex) {
                    if (cleared) {
                        plan.setPhase(FlightPlan.Phase.APPROACH);
                    } else {
                        hold(level, aircraft, plan);
                    }
                    return guide(level, aircraft, state, plan, pitch, verticalSpeed);
                }
                // Drifted well off the circuit (wind-up, slow roll-in, terrain climb): plan a new one from here.
                if (pattern.distanceTo(i, pos) > Math.max(80, 0.5 * pattern.radius)) {
                    if (++plan.patternOffTicks > 40) {
                        startPattern(aircraft, plan);
                        return guide(level, aircraft, state, plan, pitch, verticalSpeed);
                    }
                } else {
                    plan.patternOffTicks = 0;
                }
                setIf(aircraft.flapDesiredAngleVar, Takeoff.takeoffFlaps(aircraft));
                // Pure pursuit of a point ahead on the circuit: the bank that flies the arc to it.
                double lookahead = Mth.clamp(0.6 * pattern.radius, 60, 300);
                int c = Math.min(pattern.xs.length - 1, i + (int) Math.ceil(lookahead / Pattern.STEP));
                Vec3 carrot = new Vec3(pattern.xs[c], pos.y, pattern.zs[c]);
                double dist = Math.max(10, FlightPlan.horizontal(pos, carrot));
                double alpha = Math.toRadians(Mth.wrapDegrees(ApServer.yawOf(carrot.subtract(pos)) - heading));
                double curvature = 2 * Math.sin(alpha) / dist;
                double v = Math.max(groundSpeed, 5);
                double maxPatternBank = ApConfig.PATTERN_BANK.get() + 5;
                double bank = Mth.clamp(Math.toDegrees(Math.atan(v * v * curvature / gravity(aircraft))), -maxPatternBank, maxPatternBank);
                double progress = Math.min(1, (double) i / Math.max(1, pattern.targetIndex));
                double targetY = Math.max(plan.entry.y, plan.entry.y + (pattern.startAltitude - plan.entry.y) * (1 - progress));
                ApServer.terrainGuard(level, aircraft, plan, pos);
                if (!cleared) {
                    targetY += 30 * plan.queuePosition; // stacked behind the aircraft ahead of us
                }
                targetY += state.separation;
                if (plan.guardTicks > 0) {
                    targetY = Math.max(targetY, plan.guardAltitude);
                }
                state.info = String.format("CIRCUIT to %s  %.1f km to final  bank %+.0f%s%s", plan.runway.name(),
                        (pattern.targetIndex - i) * Pattern.STEP / 1000, bank,
                        cleared ? "  CLEARED TO LAND" : "  number " + (plan.queuePosition + 1),
                        plan.guardTicks > 0 ? "  TERRAIN" : state.separation > 0 ? "  TRAFFIC" : "");
                return new Guidance(bank, ApServer.altitudeToPitch(state, targetY, pos.y, verticalSpeed), targetY, patternSpeed(aircraft, plan), Double.NaN);
            }
            case APPROACH -> {
                // Arrived here without a clearance (from the route or a reposition): ask now, else hold.
                if (!TrafficControl.holds(level, plan.runway, aircraft.uniqueUUID)) {
                    TrafficControl.arrivalPosition(level, plan.runway, aircraft.uniqueUUID);
                    if (!TrafficControl.request(level, plan.runway, aircraft.uniqueUUID, true)) {
                        hold(level, aircraft, plan);
                        return new Guidance(0, ApServer.altitudeToPitch(state, plan.entry.y, pos.y, verticalSpeed), plan.entry.y, patternSpeed(aircraft, plan), Double.NaN);
                    }
                }
                if (hasGear) {
                    setIf(aircraft.retractGearVar, 0);
                }
                setIf(aircraft.flapDesiredAngleVar, Takeoff.takeoffFlaps(aircraft));
                double targetY = pos.y + (approachHeight - h);
                state.info = String.format("APPROACH %s  %.0f off centerline  %.1f km", plan.runway.name(), Math.abs(cross), distBefore / 1000);
                if (established && glideHeight(plan, distBefore) <= h + 1) {
                    plan.setPhase(FlightPlan.Phase.FINAL);
                    state.vsIntegral = pitch;
                } else if (distBefore < Math.max(150, ApConfig.APPROACH_DISTANCE.get() * 0.4) && !established) {
                    startPattern(aircraft, plan); // too close to line up properly: fly the circuit again
                }
                return new Guidance(locBank, ApServer.altitudeToPitch(state, targetY, pos.y, verticalSpeed), targetY, vapp * 1.2, Double.NaN);
            }
            case FINAL -> {
                if (hasGear) {
                    setIf(aircraft.retractGearVar, 0);
                }
                setIf(aircraft.flapDesiredAngleVar, landingFlaps(aircraft));
                double glide = glideHeight(plan, distBefore);
                double sink = groundSpeed * Math.tan(Math.toRadians(ApConfig.GLIDE_SLOPE.get()));
                double vsTarget = Mth.clamp(-sink + ApConfig.GLIDE_GAIN.get() * (glide - h), -ApConfig.MAX_VERTICAL_SPEED.get(), 1.5);
                double targetPitch = verticalSpeedPitch(state, vsTarget, verticalSpeed);
                double bank = h < 30 ? Mth.clamp(locBank, -10, 10) : locBank;
                state.info = String.format("FINAL %s  %.0f km/h (Vapp %.0f)  %+.1f glide  %.0f up", plan.runway.name(),
                        aircraft.indicatedSpeed * 3.6, vapp * 3.6, h - glide, h);
                // Something on the runway (taxiing onto it, still rolling out, not under our control): go around.
                if (h < 80 && aircraft.ticksExisted % 5 == 0 && TrafficControl.occupant(level, plan.runway, aircraft.uniqueUUID) != null) {
                    goAround(level, aircraft, plan, "runway occupied");
                    return guide(level, aircraft, state, plan, pitch, verticalSpeed);
                }
                if (h < ApConfig.DECISION_HEIGHT.get()) {
                    String unstable = Math.abs(cross) > 6 ? "off centerline"
                            : Math.abs(Mth.wrapDegrees(inYaw - heading)) > 10 ? "heading"
                            : h - glide > 8 ? "too high"
                            : hasGear && aircraft.retractGearVar.isActive ? "gear not down" : null;
                    if (unstable != null) {
                        goAround(level, aircraft, plan, unstable);
                        return guide(level, aircraft, state, plan, pitch, verticalSpeed);
                    }
                }
                if (h < ApConfig.FLARE_HEIGHT.get()) {
                    plan.setPhase(FlightPlan.Phase.FLARE);
                    state.elevatorIntegral = 0;
                    state.lastHeading = Double.NaN;
                    aircraft.lastController = null; // we drive the elevator directly from here on (see Takeoff)
                }
                return new Guidance(bank, targetPitch, pos.y + (glide - h), vapp, Double.NaN);
            }
            case GO_AROUND -> {
                setIf(aircraft.flapDesiredAngleVar, Takeoff.takeoffFlaps(aircraft));
                if (hasGear && verticalSpeed > 1 && h > ApConfig.GEAR_UP_HEIGHT.get()) {
                    setIf(aircraft.retractGearVar, 1);
                }
                double bank = Mth.clamp(Mth.wrapDegrees(inYaw - heading) * ApConfig.HEADING_GAIN.get(), -10, 10);
                state.info = String.format("GO AROUND  %.0f up", h);
                if (h > approachHeight - 5) {
                    if (plan.goArounds >= ApConfig.MAX_GO_AROUNDS.get()) {
                        plan.setPhase(FlightPlan.Phase.HOLDING); // gave up: circle at the approach entry
                    } else {
                        startPattern(aircraft, plan);
                    }
                }
                return new Guidance(bank, ApConfig.CLIMB_PITCH.get(), pos.y + approachHeight - h, vapp * 1.3, AEntityVehicleE_Powered.MAX_THROTTLE);
            }
            case REPOSITION -> {
                Vec3 target = plan.repositionPoint;
                double toTarget = Mth.wrapDegrees(ApServer.yawOf(target.subtract(pos)) - heading);
                double bank = Mth.clamp(toTarget * ApConfig.HEADING_GAIN.get(), -maxBank, maxBank);
                double targetY = pos.y + (approachHeight - h);
                double remaining = FlightPlan.horizontal(pos, target);
                state.info = String.format("REPOSITION for %s  %.1f km  (attempt %d)", plan.runway.name(), remaining / 1000, plan.goArounds + 1);
                if (remaining < ApConfig.CAPTURE_RADIUS.get()) {
                    plan.setPhase(FlightPlan.Phase.APPROACH);
                }
                return new Guidance(bank, ApServer.altitudeToPitch(state, targetY, pos.y, verticalSpeed), targetY, vapp * 1.3, Double.NaN);
            }
            default -> {
                return new Guidance(0, pitch, pos.y, state.targetSpeed, Double.NaN);
            }
        }
    }

    /**
     * Gravity as seen in world blocks/second^2. MTS integrates the physics unscaled (0.0245 blocks/tick^2
     * x gravity factor) and then moves the aircraft by motion x speedFactor (aircraftSpeedFactor,
     * 0.35 by default), so in the world every acceleration - gravity, and the sideways pull of a
     * banked wing - is scaled by the speed factor too. Turn radius = v^2 / (gravity() tan bank).
     */
    static double gravity(EntityVehicleF_Physics aircraft) {
        double factor = aircraft.gravityFactorVar.currentValue;
        double speedFactor = aircraft.speedFactor > 0 ? aircraft.speedFactor : 1;
        return 0.0245 * (factor > 0 ? factor : 1) * speedFactor * 400;
    }

    /** Circuit speed (indicated): slow enough for tight turns, with takeoff flaps. */
    static double patternSpeed(EntityVehicleF_Physics aircraft, FlightPlan plan) {
        double speed = Takeoff.liftSpeed(aircraft, Takeoff.takeoffFlaps(aircraft), 15, 1, true) * plan.stallScale * ApConfig.PATTERN_SPEED_FACTOR.get();
        return Math.max(speed, minimumSpeed(aircraft) + 3);
    }

    /** Plan the circuit from here to the approach entry, arriving on the runway heading. */
    static void startPattern(EntityVehicleF_Physics aircraft, FlightPlan plan) {
        double groundSpeed = new Vec3(aircraft.position.x - aircraft.prevPosition.x, 0, aircraft.position.z - aircraft.prevPosition.z).length() * 20;
        double v = Math.max(groundSpeed, patternSpeed(aircraft, plan)); // indicated speed is already world blocks/s
        double radius = Math.max(40, v * v / (gravity(aircraft) * Math.tan(Math.toRadians(ApConfig.PATTERN_BANK.get()))));
        double landingYaw = ApServer.yawOf(Runway.heading(plan.threshold, plan.farEnd));
        plan.pattern = Pattern.plan(aircraft.position.x, aircraft.position.z, ApServer.headingOf(aircraft),
                plan.entry.x, plan.entry.z, landingYaw, radius, aircraft.position.y);
        plan.patternOffTicks = 0;
        plan.setPhase(FlightPlan.Phase.PATTERN);
    }

    /** Pitch to fly a vertical speed: PI, the integral holds the pitch the descent needs. */
    static double verticalSpeedPitch(ApServer.State state, double vsTarget, double verticalSpeed) {
        double error = vsTarget - verticalSpeed;
        state.vsIntegral = Mth.clamp(state.vsIntegral + ApConfig.VS_KI.get() * error, -8, 12);
        return Mth.clamp(state.vsIntegral + ApConfig.VS_KP.get() * error, -8, 12);
    }

    /** Fly out to a point beyond the approach entry, offset to the side we're on, then intercept again. */
    private static void startReposition(FlightPlan plan, Vec3 pos, double cross) {
        Vec3 in = Runway.heading(plan.threshold, plan.farEnd);
        Vec3 left = new Vec3(in.z, 0, -in.x);
        double side = cross >= 0 ? 1 : -1;
        double radius = ApConfig.HOLD_RADIUS.get();
        Vec3 out = plan.threshold.subtract(in.scale(ApConfig.APPROACH_DISTANCE.get() + radius)).add(left.scale(side * radius));
        plan.repositionPoint = new Vec3(out.x, pos.y, out.z);
        plan.setPhase(FlightPlan.Phase.REPOSITION);
    }

    /** Runway not ours yet: circle at the approach entry (stacked by queue place) until it is. */
    private static void hold(ServerLevel level, EntityVehicleF_Physics aircraft, FlightPlan plan) {
        plan.waitingForRunway = true;
        plan.legStart = null;
        plan.setPhase(FlightPlan.Phase.HOLDING);
        ApServer.notifyRiders(level.getServer(), aircraft, Component.translatable("msg.ivautopilot.holding_traffic", plan.runway.name()));
    }

    private static void goAround(ServerLevel level, EntityVehicleF_Physics aircraft, FlightPlan plan, String reason) {
        TrafficControl.releaseRunway(aircraft.uniqueUUID); // let others use it while we fly round again
        Takeoff.releaseInputs(aircraft);
        setIf(aircraft.reverseThrustVar, 0);
        if (!reason.equals("runway occupied")) {
            plan.goArounds++; // traffic go-arounds don't count toward giving up
        }
        if (plan.goArounds >= ApConfig.MAX_GO_AROUNDS.get()) {
            ApServer.notifyRiders(level.getServer(), aircraft, Component.translatable("msg.ivautopilot.landing_gave_up", plan.goArounds));
        } else {
            ApServer.notifyRiders(level.getServer(), aircraft, Component.translatable("msg.ivautopilot.go_around", reason));
        }
        plan.setPhase(FlightPlan.Phase.GO_AROUND);
    }

    // ------------------------------------------------------------------------------------------
    // Flare and rollout (elevator and rudder inputs driven directly)
    // ------------------------------------------------------------------------------------------

    /** One tick of flare / rollout. Returns "landed", a cancel message key, or null to carry on. */
    @Nullable
    static String handsOn(ServerLevel level, EntityVehicleF_Physics aircraft, ApServer.State state, FlightPlan plan,
                          double pitch, double pitchRate, double verticalSpeed) {
        plan.phaseTicks++;
        if (aircraft.lastController != null) {
            Takeoff.releaseInputs(aircraft);
            return "msg.ivautopilot.landing_cancelled";
        }
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        Vec3 in = Runway.heading(plan.threshold, plan.farEnd);
        double distBefore = distBefore(pos, plan);
        double h = height(level, aircraft, plan, distBefore);
        double speed = aircraft.indicatedSpeed;

        if (plan.phase == FlightPlan.Phase.FLARE) {
            setIf(aircraft.throttleVar, 0);
            double headingError = Mth.wrapDegrees(ApServer.yawOf(in) - ApServer.headingOf(aircraft));
            ApServer.holdBank(aircraft, state, Mth.clamp(headingError, -5, 5));
            // Sink rate shrinks with height: a smooth round-out to the touchdown sink rate.
            double vsTarget = -(ApConfig.FLARE_SINK_RATE.get() + 0.4 * Math.max(0, h));
            double targetPitch = Mth.clamp(verticalSpeedPitch(state, vsTarget, verticalSpeed), -2, 10);
            Takeoff.elevator(aircraft, state, targetPitch, pitch, pitchRate, false);
            state.info = String.format("FLARE  %.1f up  sink %.1f", h, -verticalSpeed);
            if (ApServer.isOnGround(aircraft)) {
                plan.setPhase(FlightPlan.Phase.ROLLOUT);
                state.lastHeading = Double.NaN;
            } else if (-distBefore > plan.runway.length() * ApConfig.LONG_LANDING_FRACTION.get() || plan.phaseTicks > 300) {
                goAround(level, aircraft, plan, "landing long");
            }
            return null;
        }

        // ROLLOUT, like an airliner: on touchdown spoilers up, reverse thrust and firm braking down to
        // taxi speed (GAP animates the spoilers from the brake variable, so braking raises them), then
        // spoilers and reversers stowed and a gentle taxi down the runway, stopping rolloutMargin before
        // the far end. If the runway runs short, it brakes hard whatever the phase.
        double remaining = plan.runway.length() + distBefore - ApConfig.ROLLOUT_MARGIN.get(); // distBefore < 0 on the runway
        double taxiSpeed = ApConfig.TAXI_SPEED.get();
        if (!plan.rolloutTaxiing && speed < taxiSpeed + 1) {
            plan.rolloutTaxiing = true;
        }
        if (plan.rolloutTaxiing) {
            // Leave the runway by the exit that leads to our gate, once we reach it.
            if (!plan.exitChecked) {
                plan.exitChecked = true;
                plan.exitAlong = ApServer.exitAlong(level, aircraft, state, plan);
            }
            if (!Double.isNaN(plan.exitAlong) && plan.exitAlong + distBefore < 40) {
                return "exit";
            }
        }
        boolean runningShort = remaining < speed * 2.5; // under ~2.5 s of runway left at this speed
        boolean reversing = false;
        double brake, throttle = 0;
        if (!plan.rolloutTaxiing) {
            reversing = aircraft.hasReverseThrust && ApConfig.REVERSE_THROTTLE.get() > 0 && speed > taxiSpeed * 2;
            throttle = reversing ? ApConfig.REVERSE_THROTTLE.get() : 0;
            // A moment for the nose wheel to come down, then spoilers and brakes.
            brake = plan.phaseTicks < 8 ? 0 : runningShort ? 1 : ApConfig.TOUCHDOWN_BRAKE.get();
        } else {
            double stopDistance = speed * speed / (2 * 3.0) + 3; // gentle stop at the end
            if (remaining <= stopDistance || runningShort) {
                brake = 1;
            } else {
                // Hold taxi speed: a little power if slow, a touch of brake if fast.
                double error = taxiSpeed - speed;
                throttle = error > 0 ? Mth.clamp(0.06 + 0.04 * error, 0, 0.35) : 0;
                brake = error < -2 ? 0.2 : 0;
            }
        }
        setIf(aircraft.reverseThrustVar, reversing ? 1 : 0);
        setIf(aircraft.throttleVar, throttle);
        setIf(aircraft.brakeVar, brake);
        // Let the nose down gently and level the ailerons.
        setIf(aircraft.elevatorInputVar, approach(aircraft.elevatorInputVar.currentValue, 0, 1));
        setIf(aircraft.elevatorTrimVar, approach(aircraft.elevatorTrimVar.currentValue, 0, 0.3));
        setIf(aircraft.aileronTrimVar, approach(aircraft.aileronTrimVar.currentValue, 0, 0.5));
        setIf(aircraft.rudderTrimVar, approach(aircraft.rudderTrimVar.currentValue, 0, 0.5));
        Takeoff.steer(aircraft, state, pos, plan.threshold, in);
        state.info = String.format("%s %s  %.0f km/h  %.0f to go%s%s", plan.rolloutTaxiing ? "TAXI" : "ROLLOUT", plan.runway.name(),
                speed * 3.6, Math.max(0, remaining), reversing ? "  REV" : "", brake > 0.3 ? "  SPOILERS/BRAKES" : "");
        if (Math.abs(speed) < 1 && plan.phaseTicks > 20 && (remaining < 60 || plan.phaseTicks > 1200)) {
            setIf(aircraft.parkingBrakeVar, 1);
            setIf(aircraft.brakeVar, 0);
            setIf(aircraft.throttleVar, 0);
            setIf(aircraft.reverseThrustVar, 0);
            setIf(aircraft.flapDesiredAngleVar, Takeoff.flapNotch(aircraft, 0));
            Takeoff.releaseInputs(aircraft);
            return "landed";
        }
        return null;
    }

    private static double approach(double value, double target, double step) {
        return value + Mth.clamp(target - value, -step, step);
    }
}
