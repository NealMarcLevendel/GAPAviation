package ivautopilot;

import ivautopilot.nav.FlightPlan;
import ivautopilot.nav.Runway;
import ivautopilot.nav.RunwayRegistry;
import minecrafttransportsimulator.baseclasses.ComputedVariable;
import minecrafttransportsimulator.entities.instances.AEntityVehicleE_Powered;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2: automatic takeoff.
 *
 *  TAKEOFF_ROLL  engines started if needed, brakes off, takeoff flaps, full throttle. Nose-wheel
 *                steering (rudder) holds the runway centerline. Aborts if rotation speed isn't
 *                reached by abortFraction of the runway.
 *  ROTATE        at rotation speed, elevator raises the nose to rotatePitch; lifts off.
 *  CLIMB_OUT     climbPitch, wings level on runway heading, gear up, flaps up, then hand over to
 *                the en-route navigation.
 *
 * During takeoff the elevator and rudder INPUTS are driven directly (trim alone can't rotate a
 * heavy jet or steer the nose wheel). MTS's client only re-centres those inputs for the aircraft's
 * "last controller", which we clear when the takeoff starts - so if the pilot touches the flight
 * controls (making themselves last controller again), the autopilot hands over immediately.
 */
final class Takeoff {
    /** Departure runway and direction found under the aircraft. */
    record Departure(Runway runway, Vec3 start, Vec3 end) {
    }

    private Takeoff() {
    }

    /** The runway the aircraft is lined up on (within 15 blocks of the centerline, in the first half, heading within 30 degrees). */
    @Nullable
    static Departure findDeparture(ServerLevel level, EntityVehicleF_Physics aircraft) {
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        double heading = ApServer.headingOf(aircraft);
        Departure best = null;
        double bestCross = Double.MAX_VALUE;
        for (Runway runway : RunwayRegistry.get(level).all(level.dimension())) {
            for (int i = 0; i < 2; i++) {
                Vec3 start = i == 0 ? runway.a() : runway.b(), end = i == 0 ? runway.b() : runway.a();
                Vec3 dir = Runway.heading(start, end);
                double along = (pos.x - start.x) * dir.x + (pos.z - start.z) * dir.z;
                double cross = Math.abs(crossTrack(pos, start, dir));
                double aligned = Math.abs(Mth.wrapDegrees(ApServer.yawOf(dir) - heading));
                if (cross < 15 && along > -30 && along < runway.length() * 0.5 && aligned < 30 && cross < bestCross) {
                    best = new Departure(runway, start, end);
                    bestCross = cross;
                }
            }
        }
        return best;
    }

    /** Signed distance from the centerline: positive = to the LEFT of it (MC: +x is left when facing +z). */
    static double crossTrack(Vec3 pos, Vec3 start, Vec3 dir) {
        return (pos.x - start.x) * dir.z - (pos.z - start.z) * dir.x;
    }

    /** Speed (indicated, blocks/s) at which the wing at rotatePitch lifts the aircraft's weight x margin (MTS's own lift formula). */
    static double rotationSpeed(EntityVehicleF_Physics aircraft, double flapAngle) {
        return liftSpeed(aircraft, flapAngle, Math.min(ApConfig.ROTATE_PITCH.get(), 18), ApConfig.ROTATE_MARGIN.get());
    }

    /**
     * Indicated speed (blocks/s) at which the wing at this angle of attack lifts weight x margin, from
     * MTS's lift model: CL = (2 + flap/350) * sin(pi/2 * aoa/15), lift = 0.5 rho v^2 S CL. At aoa 15 it
     * is the stall speed.
     */
    static double liftSpeed(EntityVehicleF_Physics aircraft, double flapAngle, double aoa, double margin) {
        return liftSpeed(aircraft, flapAngle, aoa, margin, false);
    }

    /**
     * With includeTail, the tail's lift (MTS subtracts it from the wing's: total = wing - elevator,
     * elevator CL taken at -2.5 + aoa - (elevator angle + trim)) is included too. That matters near
     * the stall, so stall speeds use it.
     */
    static double liftSpeed(EntityVehicleF_Physics aircraft, double flapAngle, double aoa, double margin, boolean includeTail) {
        double wingArea = aircraft.wingAreaVar.currentValue;
        double lift = wingArea * liftCoeff(aoa, 2 + flapAngle / EntityVehicleF_Physics.MAX_FLAP_ANGLE_REFERENCE);
        if (includeTail) {
            double elevator = aircraft.elevatorAngleVar.currentValue + aircraft.elevatorTrimVar.currentValue;
            lift -= aircraft.elevatorAreaVar.currentValue * liftCoeff(-2.5 + aoa - elevator, 2);
        }
        double weight = aircraft.currentMass * 0.0245 * aircraft.gravityFactorVar.currentValue;
        if (lift <= 0.05 * wingArea || aircraft.airDensity <= 0 || wingArea <= 0) {
            return 30;
        }
        double axial = Math.sqrt(2 * margin * weight / (aircraft.airDensity * lift));
        return axial * aircraft.speedFactor * 20;
    }

    /** MTS's lift curve (EntityVehicleF_Physics.getLiftCoeff). */
    static double liftCoeff(double aoa, double max) {
        double a = Math.abs(aoa);
        if (aoa == 0) {
            return 0;
        } else if (a <= 15 * 1.25) {
            return max * Math.sin(Math.PI / 2 * aoa / 15);
        } else if (a <= 15 * 1.5) {
            return aoa > 0 ? max * (0.4 + 1 / (aoa - 15)) : max * (-0.4 + 1 / (aoa + 15));
        } else {
            return max * Math.sin(Math.PI / 6 * aoa / 15);
        }
    }

    /** Runs one tick of the takeoff. Returns a message key if the takeoff ended abnormally, else null. */
    @Nullable
    static String tick(EntityVehicleF_Physics aircraft, ApServer.State state, FlightPlan plan, double pitch, double pitchRate, double verticalSpeed) {
        plan.phaseTicks++;
        Vec3 pos = new Vec3(aircraft.position.x, aircraft.position.y, aircraft.position.z);
        Vec3 dir = Runway.heading(plan.departureStart, plan.departureEnd);
        double runwayYaw = ApServer.yawOf(dir);
        double height = pos.y - plan.departureStart.y;
        double length = plan.departure.length();
        double along = (pos.x - plan.departureStart.x) * dir.x + (pos.z - plan.departureStart.z) * dir.z;

        // The pilot touched the flight controls: they have control.
        if (aircraft.lastController != null) {
            releaseInputs(aircraft);
            return "msg.ivautopilot.takeoff_cancelled";
        }

        switch (plan.phase) {
            case TAKEOFF_ROLL -> {
                setIf(aircraft.parkingBrakeVar, 0);
                setIf(aircraft.brakeVar, 0);
                setIf(aircraft.retractGearVar, 0);
                double flaps = takeoffFlaps(aircraft);
                setIf(aircraft.flapDesiredAngleVar, flaps);
                if (!startEngines(aircraft)) {
                    setIf(aircraft.throttleVar, 0);
                    state.info = "STARTING ENGINES";
                    steer(aircraft, state, pos, plan.departureStart, dir);
                    return plan.phaseTicks > 400 ? "msg.ivautopilot.engines_failed" : null;
                }
                if (plan.rotateSpeed <= 0) {
                    plan.rotateSpeed = rotationSpeed(aircraft, flaps);
                }
                setIf(aircraft.throttleVar, AEntityVehicleE_Powered.MAX_THROTTLE);
                steer(aircraft, state, pos, plan.departureStart, dir);
                elevator(aircraft, state, 0, pitch, pitchRate, true);
                state.info = String.format("TAKEOFF ROLL  %.0f / %.0f km/h  runway %.0f%%", aircraft.indicatedSpeed * 3.6,
                        plan.rotateSpeed * 3.6, 100 * Math.max(0, along) / length);
                if (!ApServer.isOnGround(aircraft) && height > 1 && aircraft.indicatedSpeed > plan.rotateSpeed * 0.7) {
                    plan.setPhase(FlightPlan.Phase.CLIMB_OUT); // flew itself off before rotation
                } else if (aircraft.indicatedSpeed >= plan.rotateSpeed) {
                    plan.setPhase(FlightPlan.Phase.ROTATE);
                } else if (along > length * ApConfig.ABORT_FRACTION.get()) {
                    return "msg.ivautopilot.takeoff_aborted";
                }
            }
            case ROTATE -> {
                setIf(aircraft.throttleVar, AEntityVehicleE_Powered.MAX_THROTTLE);
                steer(aircraft, state, pos, plan.departureStart, dir);
                double target = Math.min(ApConfig.ROTATE_PITCH.get(), plan.phaseTicks * 0.2); // ~3 deg/s, no tail strike
                elevator(aircraft, state, target, pitch, pitchRate, true);
                state.info = String.format("ROTATE  %.0f km/h  pitch %+.0f", aircraft.indicatedSpeed * 3.6, pitch);
                if (!ApServer.isOnGround(aircraft) && height > 1) {
                    plan.setPhase(FlightPlan.Phase.CLIMB_OUT);
                } else if (along > length) {
                    return "msg.ivautopilot.takeoff_aborted";
                }
            }
            case CLIMB_OUT -> {
                setIf(aircraft.throttleVar, AEntityVehicleE_Powered.MAX_THROTTLE);
                // Centre the nose wheel / rudder; hold runway heading with gentle banking.
                double rudder = aircraft.rudderInputVar.currentValue;
                setIf(aircraft.rudderInputVar, Math.abs(rudder) < 1 ? 0 : rudder - Math.signum(rudder));
                double headingError = Mth.wrapDegrees(runwayYaw - ApServer.headingOf(aircraft));
                ApServer.holdBank(aircraft, state, Mth.clamp(headingError * ApConfig.HEADING_GAIN.get(), -10, 10));
                double target = Math.min(ApConfig.CLIMB_PITCH.get(), ApConfig.ROTATE_PITCH.get() + plan.phaseTicks * 0.1);
                if (state.stallProtect) {
                    target = Math.min(target, !Double.isNaN(state.aoa) && state.aoa > ApConfig.AOA_LIMIT.get() - 2
                            ? pitch - 2 * (state.aoa - ApConfig.AOA_LIMIT.get()) - 1 : Math.max(0, pitch - 1));
                }
                elevator(aircraft, state, target, pitch, pitchRate, true);
                boolean hasGear = aircraft.definition.motorized.gearSequenceDuration != 0;
                if (hasGear && height > ApConfig.GEAR_UP_HEIGHT.get() && verticalSpeed > 0) {
                    setIf(aircraft.retractGearVar, 1);
                }
                if (height > ApConfig.FLAPS_UP_HEIGHT.get()) {
                    setIf(aircraft.flapDesiredAngleVar, flapNotch(aircraft, 0));
                }
                state.info = String.format("CLIMB OUT  %.0f above runway  %.0f km/h", height, aircraft.indicatedSpeed * 3.6);
                if (height > ApConfig.CLIMB_OUT_HEIGHT.get()) {
                    // Hand the pitch over from the elevator to the trim, and start the route.
                    double elevatorNow = aircraft.elevatorInputVar.currentValue;
                    double trim = Mth.clamp(aircraft.elevatorTrimVar.currentValue + elevatorNow, -EntityVehicleF_Physics.MAX_ELEVATOR_TRIM, EntityVehicleF_Physics.MAX_ELEVATOR_TRIM);
                    setIf(aircraft.elevatorTrimVar, trim);
                    releaseInputs(aircraft);
                    state.pitchIntegral = trim;
                    state.vsIntegral = pitch;
                    state.bankIntegral = aircraft.aileronTrimVar.currentValue;
                    state.throttleIntegral = Double.NaN;
                    state.speedCommand = Double.NaN;
                    plan.setPhase(FlightPlan.Phase.ENROUTE);
                    plan.replanTimer = 0;
                    plan.route = null;
                    plan.legStart = pos;
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** Abort / cancel: idle, brakes on if still on the ground, controls centred. */
    static void abort(EntityVehicleF_Physics aircraft) {
        setIf(aircraft.throttleVar, 0);
        if (ApServer.isOnGround(aircraft)) {
            setIf(aircraft.brakeVar, 1);
        }
        releaseInputs(aircraft);
    }

    /** Steer along the runway centerline with nose-wheel steering (rudder input). */
    static void steer(EntityVehicleF_Physics aircraft, ApServer.State state, Vec3 pos, Vec3 start, Vec3 dir) {
        double runwayYaw = ApServer.yawOf(dir);
        double cross = crossTrack(pos, start, dir); // + = left of centerline
        double desired = runwayYaw + Mth.clamp(ApConfig.CENTERLINE_GAIN.get() * cross, -15, 15); // left of line -> turn right
        double heading = ApServer.headingOf(aircraft);
        double error = Mth.wrapDegrees(desired - heading);
        // Learn which way the rudder turns us (positive rudder should turn right = increase yaw).
        if (!Double.isNaN(state.lastHeading) && aircraft.indicatedSpeed > 3 && Math.abs(aircraft.rudderInputVar.currentValue) > 3) {
            double yawRate = Mth.wrapDegrees(heading - state.lastHeading) * 20;
            if (Math.abs(yawRate) > 1) {
                state.rudderSign = Math.signum(yawRate * aircraft.rudderInputVar.currentValue);
            }
        }
        state.lastHeading = heading;
        double rudder = Mth.clamp(state.rudderSign * ApConfig.STEER_GAIN.get() * error, -EntityVehicleF_Physics.MAX_RUDDER_ANGLE, EntityVehicleF_Physics.MAX_RUDDER_ANGLE);
        setIf(aircraft.rudderInputVar, rudder);
    }

    /** Pitch control through the elevator INPUT (PID), optionally with the trim neutralised. */
    static void elevator(EntityVehicleF_Physics aircraft, ApServer.State state, double targetPitch, double pitch, double pitchRate, boolean neutralTrim) {
        if (neutralTrim) {
            setIf(aircraft.elevatorTrimVar, 0);
        }
        double error = targetPitch - pitch;
        double max = EntityVehicleF_Physics.MAX_ELEVATOR_ANGLE;
        state.elevatorIntegral = Mth.clamp(state.elevatorIntegral + ApConfig.PITCH_KI.get() * error, -max, max);
        double command = Mth.clamp(ApConfig.PITCH_KP.get() * 2 * error + state.elevatorIntegral - ApConfig.PITCH_KD.get() * pitchRate, -max, max);
        double current = aircraft.elevatorInputVar.currentValue;
        setIf(aircraft.elevatorInputVar, current + Mth.clamp(command - current, -1.5, 1.5));
    }

    /** Magnetos on and starters engaged until every engine runs. Returns true when all are running. */
    private static boolean startEngines(EntityVehicleF_Physics aircraft) {
        boolean allRunning = true;
        for (PartEngine engine : new ArrayList<>(aircraft.engines)) {
            setIf(engine.magnetoVar, 1);
            if (engine.running) {
                setIf(engine.electricStarterVar, 0);
            } else {
                allRunning = false;
                setIf(engine.electricStarterVar, 1);
            }
        }
        return allRunning;
    }

    static double takeoffFlaps(EntityVehicleF_Physics aircraft) {
        List<Float> notches = aircraft.definition.motorized.flapNotches;
        return notches == null || notches.size() < 2 ? flapNotch(aircraft, 0) : notches.get(1);
    }

    static double flapNotch(EntityVehicleF_Physics aircraft, int index) {
        List<Float> notches = aircraft.definition.motorized.flapNotches;
        return notches == null || notches.isEmpty() ? 0 : notches.get(Math.min(index, notches.size() - 1));
    }

    static void releaseInputs(EntityVehicleF_Physics aircraft) {
        setIf(aircraft.elevatorInputVar, 0);
        setIf(aircraft.rudderInputVar, 0);
    }

    static void setIf(ComputedVariable variable, double value) {
        if (Math.abs(variable.currentValue - value) > 1.0E-3) {
            variable.setTo(value, true);
        }
    }
}
