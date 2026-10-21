package gapcabinwalk.feel;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings for head inertia (config/gapcabinwalk-client.toml). */
public final class HeadInertiaConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED, TILT_WHEN_STANDING;
    public static final ForgeConfigSpec.DoubleValue STRENGTH, ROLL_STRENGTH, ACCELERATION_STRENGTH, FREQUENCY, DAMPING, MAX_ANGLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Head inertia: in first person, your head lags behind the vehicle's turns and accelerations",
                "like a real passenger's (toggle in game with the key under Controls > GAP Cabin Walk)").push("headInertia");
        ENABLED = b.define("enabled", true);
        STRENGTH = b.comment("How much the head lags when the vehicle starts or stops rotating (roll, pitch, yaw). 1 = realistic").defineInRange("strength", 1.0, 0, 5);
        ROLL_STRENGTH = b.comment("Extra multiplier for the roll part (rolling into / out of a bank), on top of strength").defineInRange("rollStrength", 2.0, 0, 10);
        TILT_WHEN_STANDING = b.comment("When walking the cabin, bank your view with the aircraft (like when seated) instead of keeping the horizon level").define("tiltWhenStanding", true);
        ACCELERATION_STRENGTH = b.comment("How much the head nods with speeding up / braking and sways with bumps and sideways pushes").defineInRange("accelerationStrength", 1.0, 0, 5);
        FREQUENCY = b.comment("Neck stiffness as a natural frequency, Hz (lower = floppier, slower to catch up)").defineInRange("frequency", 1.4, 0.3, 5);
        DAMPING = b.comment("Damping ratio (lower = more wobble/overshoot, 1 = no overshoot)").defineInRange("damping", 0.45, 0.05, 2);
        MAX_ANGLE = b.comment("Largest head offset, degrees").defineInRange("maxAngle", 10.0, 1, 30);
        b.pop();
        SPEC = b.build();
    }

    private HeadInertiaConfig() {
    }
}
