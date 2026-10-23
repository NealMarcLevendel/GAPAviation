package ivautopilot.track;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings for the plane tracker (config/ivautopilot-client.toml); also set with /planetracker. */
public final class TrackerClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue ICON_SIZE;
    public static final ForgeConfigSpec.DoubleValue TEXT_SCALE;
    public static final ForgeConfigSpec.BooleanValue SHOW_LABELS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Plane tracker on JourneyMap's map").push("tracker");
        ICON_SIZE = b.comment("Plane icon size on the map, pixels").defineInRange("iconSize", 36, 8, 128);
        TEXT_SCALE = b.comment("Label text size (JourneyMap's default is 1)").defineInRange("textScale", 1.75, 0.5, 4.0);
        SHOW_LABELS = b.comment("Show the label under each plane (name, altitude, speed, destination, ETA); hover still shows details")
                .define("showLabels", true);
        b.pop();
        SPEC = b.build();
    }

    private TrackerClientConfig() {
    }
}
