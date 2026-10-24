package ivautopilot;

import net.minecraftforge.common.ForgeConfigSpec;

/** Controller gains and limits. Tweak in serverconfig/ivautopilot-server.toml without rebuilding. */
public final class ApConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue PITCH_KP, PITCH_KI, PITCH_KD, MAX_TRIM_RATE;
    public static final ForgeConfigSpec.DoubleValue ALT_KP, ALT_KD, MAX_PITCH;
    public static final ForgeConfigSpec.DoubleValue AUTO_TRIM_RATE;
    public static final ForgeConfigSpec.DoubleValue BANK_KP, BANK_KI, BANK_KD, MAX_BANK, HEADING_GAIN;
    public static final ForgeConfigSpec.DoubleValue THROTTLE_KP, THROTTLE_KI, DEFAULT_CRUISE_SPEED;
    public static final ForgeConfigSpec.DoubleValue LOOKAHEAD, CAPTURE_RADIUS, HOLD_RADIUS, TERRAIN_CLEARANCE, GUARD_CLEARANCE;
    public static final ForgeConfigSpec.DoubleValue ROTATE_PITCH, CLIMB_PITCH, ROTATE_MARGIN, GEAR_UP_HEIGHT, FLAPS_UP_HEIGHT, CLIMB_OUT_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue STEER_GAIN, CENTERLINE_GAIN, ABORT_FRACTION, MIN_RUNWAY_FRACTION;
    public static final ForgeConfigSpec.DoubleValue GLIDE_SLOPE, APPROACH_SPEED_FACTOR, LOCALIZER_GAIN, GLIDE_GAIN, VS_KP, VS_KI;
    public static final ForgeConfigSpec.DoubleValue DECISION_HEIGHT, FLARE_HEIGHT, FLARE_SINK_RATE, LONG_LANDING_FRACTION, REVERSE_THROTTLE, ROLLOUT_BRAKE;
    public static final ForgeConfigSpec.IntValue MAX_GO_AROUNDS;
    public static final ForgeConfigSpec.DoubleValue ROLLOUT_MARGIN, ALARM_MARGIN, TOUCHDOWN_BRAKE, TAXI_SPEED;
    public static final ForgeConfigSpec.DoubleValue CHUNK_LOOKAHEAD_SECONDS, CHUNK_MIN_SPEED;
    public static final ForgeConfigSpec.DoubleValue AUTO_LINK_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue LOAD_FOR_PILOTED, TRACKER_ENABLED, TAXI_TO_GATE;
    public static final ForgeConfigSpec.DoubleValue CLOUD_HEIGHT, JET_ABOVE_CLOUDS, TURBOPROP_ABOVE_CLOUDS, PISTON_ABOVE_CLOUDS, TRIP_PER_BLOCK;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_CEILING, NO_BANK_RUDDER_GAIN;
    public static final ForgeConfigSpec.DoubleValue PATTERN_BANK, PATTERN_DISTANCE, PATTERN_SPEED_FACTOR, AOA_LIMIT;
    public static final ForgeConfigSpec.DoubleValue APPROACH_DISTANCE, APPROACH_HEIGHT, DESCENT_GRADIENT, MAX_VERTICAL_SPEED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Pitch hold: trim command = KP*error + KI*integral(error) - KD*pitchRate").push("pitch");
        PITCH_KP = b.comment("Degrees of trim per degree of pitch error").defineInRange("kp", 0.6, 0, 20);
        PITCH_KI = b.comment("Degrees of trim per (degree of pitch error x tick)").defineInRange("ki", 0.004, 0, 1);
        PITCH_KD = b.comment("Degrees of trim per (degree/second of pitch rate) - damping").defineInRange("kd", 0.25, 0, 20);
        MAX_TRIM_RATE = b.comment("Fastest the autopilot may move the trim, degrees per tick").defineInRange("maxTrimRate", 0.5, 0.01, 10);
        b.pop();
        b.comment("Altitude hold: climb rate = KP*(target - altitude), limited to nav.maxVerticalSpeed; pitch limited to maxPitch").push("altitude");
        ALT_KP = b.comment("Climb/descent rate (blocks/second) per block of altitude error; the rate is then flown with landing.vsKp/vsKi").defineInRange("kp", 0.25, 0, 10);
        ALT_KD = b.comment("Unused since the vertical-speed loop (kept so old config files stay valid)").defineInRange("kd", 0.6, 0, 10);
        MAX_PITCH = b.comment("Largest nose-up / nose-down pitch the altitude hold will command, degrees").defineInRange("maxPitch", 10.0, 1, 30);
        b.pop();
        b.comment("Auto-trim while flying by hand").push("autotrim");
        AUTO_TRIM_RATE = b.comment("Fraction of the held elevator moved into trim each tick").defineInRange("rate", 0.01, 0, 0.5);
        b.pop();
        b.comment("Bank (roll) hold through aileron trim, and heading -> bank").push("bank");
        BANK_KP = b.comment("Bank error -> wanted roll rate (0.3 = 1 deg/s per degree; scales the built-in gain)").defineInRange("kp", 0.3, 0, 20);
        BANK_KI = b.comment("Roll-rate integral (0.002 = built-in default; scales it)").defineInRange("ki", 0.002, 0, 1);
        BANK_KD = b.comment("Roll-rate error -> aileron trim (0.15 = built-in default; scales it). The autopilot also lowers this per aircraft by itself if it overshoots").defineInRange("kd", 0.15, 0, 20);
        MAX_BANK = b.comment("Steepest bank used for turns, degrees").defineInRange("maxBank", 25.0, 5, 45);
        HEADING_GAIN = b.comment("Degrees of bank per degree of heading error").defineInRange("headingGain", 1.0, 0.1, 10);
        b.pop();
        b.comment("Autothrottle: throttle (0..1) from airspeed error in blocks/second").push("throttle");
        THROTTLE_KP = b.defineInRange("kp", 0.03, 0, 1);
        THROTTLE_KI = b.defineInRange("ki", 0.0005, 0, 0.1);
        DEFAULT_CRUISE_SPEED = b.comment("Cruise airspeed when none is given (blocks/second; 1 block/s = 3.6 km/h)").defineInRange("defaultCruiseSpeed", 60.0, 10, 400);
        b.pop();
        b.comment("Automatic takeoff").push("takeoff");
        ROTATE_PITCH = b.comment("Pitch to rotate to at rotation speed, degrees").defineInRange("rotatePitch", 8.0, 2, 20);
        CLIMB_PITCH = b.comment("Pitch held during the initial climb, degrees").defineInRange("climbPitch", 10.0, 2, 25);
        ROTATE_MARGIN = b.comment("Rotation speed = speed where wing lift at rotatePitch equals weight x this").defineInRange("rotateMargin", 1.15, 0.8, 2.0);
        GEAR_UP_HEIGHT = b.comment("Retract the gear this many blocks above the runway").defineInRange("gearUpHeight", 10.0, 2, 100);
        FLAPS_UP_HEIGHT = b.comment("Retract the flaps this many blocks above the runway").defineInRange("flapsUpHeight", 40.0, 5, 300);
        CLIMB_OUT_HEIGHT = b.comment("Start following the route this many blocks above the runway").defineInRange("climbOutHeight", 60.0, 10, 500);
        STEER_GAIN = b.comment("Degrees of rudder (nose-wheel steering) per degree of heading error on the runway").defineInRange("steerGain", 3.0, 0.1, 30);
        CENTERLINE_GAIN = b.comment("Degrees of heading correction per block off the runway centerline").defineInRange("centerlineGain", 2.0, 0, 20);
        MIN_RUNWAY_FRACTION = b.comment("Take off from where the taxiway joins the runway if at least this fraction of the runway is left ahead;",
                "otherwise backtrack to the runway end and turn around first").defineInRange("minRunwayFraction", 0.6, 0.2, 1.0);
        ABORT_FRACTION = b.comment("Abort the takeoff if not at rotation speed after this fraction of the runway").defineInRange("abortFraction", 0.75, 0.3, 1.0);
        b.pop();
        b.comment("Automatic approach and landing. Heights are of the wheels above the runway.").push("landing");
        GLIDE_SLOPE = b.comment("Glide path angle, degrees").defineInRange("glideSlope", 3.0, 1, 8);
        APPROACH_SPEED_FACTOR = b.comment("Final approach speed = stall speed with full flaps x this").defineInRange("approachSpeedFactor", 1.3, 1.05, 2.5);
        LOCALIZER_GAIN = b.comment("Degrees of intercept angle per block off the extended centerline (max 45)").defineInRange("localizerGain", 0.5, 0.05, 5);
        GLIDE_GAIN = b.comment("Blocks/second of extra climb or sink per block off the glide path").defineInRange("glideGain", 0.2, 0, 2);
        VS_KP = b.comment("Degrees of pitch per block/second of vertical speed error").defineInRange("vsKp", 1.2, 0, 10);
        VS_KI = b.comment("Degrees of pitch per (block/second of vertical speed error x tick)").defineInRange("vsKi", 0.01, 0, 1);
        DECISION_HEIGHT = b.comment("Below this, an unstable approach (off centerline / heading / glide path) goes around").defineInRange("decisionHeight", 12.0, 3, 100);
        FLARE_HEIGHT = b.comment("Start the flare this high above the runway").defineInRange("flareHeight", 5.0, 1, 30);
        FLARE_SINK_RATE = b.comment("Sink rate aimed for at touchdown, blocks/second").defineInRange("flareSinkRate", 0.5, 0.1, 3);
        LONG_LANDING_FRACTION = b.comment("Go around if not on the ground by this fraction of the runway").defineInRange("longLandingFraction", 0.5, 0.2, 0.9);
        REVERSE_THROTTLE = b.comment("Reverse thrust throttle from touchdown until twice taxi speed (aircraft that have reversers)").defineInRange("reverseThrottle", 0.8, 0, 1);
        ROLLOUT_BRAKE = b.comment("Unused (replaced by touchdownBrake; kept so old config files stay valid)").defineInRange("rolloutBrake", 0.6, 0, 1);
        ROLLOUT_MARGIN = b.comment("After slowing to taxi speed, taxi on and stop this far before the far end of the runway, blocks").defineInRange("rolloutMargin", 30.0, 0, 500);
        TOUCHDOWN_BRAKE = b.comment("Brake (0..1) from touchdown down to taxi speed. GAP raises the spoilers with the brake, so this is also how far they come up").defineInRange("touchdownBrake", 0.85, 0, 1);
        TAXI_SPEED = b.comment("Taxi speed after the landing roll, blocks/second (1 block/s = 3.6 km/h)").defineInRange("taxiSpeed", 8.0, 2, 30);
        TAXI_TO_GATE = b.comment("After landing, clear the runway by taxiing to the nearest free gate (named taxi waypoint), if the taxi network reaches one").define("taxiToGate", true);
        ALARM_MARGIN = b.comment("Stay this many blocks/second above the speed at which the plane's own stall alarm sounds (if its pack defines one)").defineInRange("alarmMargin", 3.0, 0, 30);
        MAX_GO_AROUNDS = b.comment("After this many go-arounds, give up and hold").defineInRange("maxGoArounds", 3, 1, 10);
        b.pop();
        b.comment("No-bank zones: past the end of the departure runway, and inside Airport Marker zones").push("nobank");
        AIRPORT_CEILING = b.comment("Airport no-bank zones reach this many blocks above the Airport Marker").defineInRange("airportCeiling", 100.0, 10, 1000);
        NO_BANK_RUDDER_GAIN = b.comment("Wings-level turns: degrees of rudder trim per degree of heading error").defineInRange("rudderGain", 0.5, 0, 5);
        b.pop();
        b.comment("Stall protection").push("protection");
        AOA_LIMIT = b.comment("Angle of attack (degrees) at which stall protection lowers the nose, levels the wings and adds full power. MTS wings stall around 15-19.")
                .defineInRange("aoaLimit", 11.0, 5, 16);
        b.pop();
        b.comment("Cruise altitude when /autopilot fly isn't given one: higher for longer trips, up to a ceiling that depends on the engines").push("cruise");
        CLOUD_HEIGHT = b.comment("Height of the cloud layer (vanilla 1.18+: 192)").defineInRange("cloudHeight", 192.0, -64, 2000);
        JET_ABOVE_CLOUDS = b.comment("Jets (turbofan/turbojet, no propellers) cruise up to this far above the clouds").defineInRange("jetAboveClouds", 100.0, -500, 2000);
        TURBOPROP_ABOVE_CLOUDS = b.comment("Turboprops (jet engines driving propellers) cruise up to this far above the clouds").defineInRange("turbopropAboveClouds", 40.0, -500, 2000);
        PISTON_ABOVE_CLOUDS = b.comment("Piston-engined planes cruise up to this far above the clouds (negative = below)").defineInRange("pistonAboveClouds", -30.0, -500, 2000);
        TRIP_PER_BLOCK = b.comment("Blocks of trip distance per block of cruise height above the higher runway (so short trips stay low)").defineInRange("tripPerBlockOfHeight", 30.0, 5, 500);
        b.pop();
        b.comment("Chunk loading: keep aircraft in loaded, ticking chunks (Minecraft freezes and hides entities",
                "that fly into chunks that aren't loaded yet, e.g. new terrain at speed)").push("chunks");
        CHUNK_LOOKAHEAD_SECONDS = b.comment("Load (not tick) a corridor this many seconds of flight ahead of the aircraft (capped at 600 blocks). Higher = safer over new terrain, more server load").defineInRange("corridorSeconds", 5.0, 0, 30);
        LOAD_FOR_PILOTED = b.comment("Also for aircraft flown by hand (anyone aboard), not only with the autopilot engaged").define("loadForPiloted", true);
        CHUNK_MIN_SPEED = b.comment("Hand-flown aircraft get chunks loaded above this ground speed, blocks/second").defineInRange("minSpeed", 10.0, 0, 200);
        b.pop();
        b.comment("Plane tracker: send every player the positions and autopilot paths of all aircraft once a second",
                "(shown on JourneyMap's map if it's installed)").push("tracker");
        TRACKER_ENABLED = b.define("enabled", true);
        b.pop();
        b.comment("Taxiway network").push("taxi");
        AUTO_LINK_DISTANCE = b.comment("A placed taxi waypoint links itself to waypoints up to this far away (one per direction, clear path only); 0 = off, link with the wand").defineInRange("autoLinkDistance", 48.0, 0, 200);
        b.pop();
        b.comment("Navigation").push("nav");
        LOOKAHEAD = b.comment("How far ahead along the route leg to aim, blocks").defineInRange("lookahead", 250.0, 50, 2000);
        CAPTURE_RADIUS = b.comment("Distance at which a waypoint counts as reached, blocks").defineInRange("captureRadius", 150.0, 20, 1000);
        HOLD_RADIUS = b.comment("Radius of the holding circle at the approach entry point, blocks").defineInRange("holdRadius", 350.0, 100, 2000);
        TERRAIN_CLEARANCE = b.comment("Route planning clearance above generated terrain, blocks").defineInRange("terrainClearance", 50.0, 10, 300);
        GUARD_CLEARANCE = b.comment("Terrain guard: climb if real ground ahead comes within this many blocks").defineInRange("guardClearance", 30.0, 5, 200);
        PATTERN_BANK = b.comment("Bank angle the circuit to the runway is planned with, degrees (turn radius = v^2 / (g tan bank))").defineInRange("patternBank", 30.0, 10, 45);
        PATTERN_DISTANCE = b.comment("Within this distance of the approach entry, fly a planned circuit that arrives lined up").defineInRange("patternDistance", 4000.0, 500, 20000);
        PATTERN_SPEED_FACTOR = b.comment("Circuit speed = stall speed with takeoff flaps x this (slower = tighter turns)").defineInRange("circuitSpeedFactor", 1.6, 1.2, 3);
        APPROACH_DISTANCE = b.comment("Approach entry point distance before the runway threshold, blocks").defineInRange("approachDistance", 600.0, 150, 5000);
        APPROACH_HEIGHT = b.comment("Approach entry point height above the runway, blocks").defineInRange("approachHeight", 40.0, 10, 500);
        DESCENT_GRADIENT = b.comment("Descent toward the entry point: blocks down per block forward").defineInRange("descentGradient", 0.07, 0.01, 0.3);
        MAX_VERTICAL_SPEED = b.comment("Altitude changes are limited to this vertical speed, blocks/second").defineInRange("maxVerticalSpeed", 8.0, 1, 50);
        b.pop();
        SPEC = b.build();
    }

    private ApConfig() {
    }
}
