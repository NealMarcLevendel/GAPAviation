package ivautopilot;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * IV Autopilot - pitch / altitude hold and auto-trim for any Immersive Vehicles fixed-wing aircraft.
 *
 * The autopilot runs on the server and flies the aircraft through its PITCH TRIM (like a real
 * autopilot's trim servo), so the pilot's own elevator input still adds on top and can override it.
 *
 * Modes (key G cycles): OFF -> PITCH HOLD -> ALTITUDE HOLD -> OFF.  Up/Down arrows change the target.
 * NAV: /autopilot fly <runway> flies a terrain-aware route to that runway's approach and holds there.
 * Runways are defined by two Runway Marker blocks with the same (anvil) name, one at each end.
 * Auto-trim (key O): while flying by hand, trim slowly takes over any elevator you keep holding.
 * Gains are in config/ivautopilot-server.toml (per world: saves/<world>/serverconfig/).
 */
@Mod(IvAutopilot.MODID)
public class IvAutopilot {
    public static final String MODID = "ivautopilot";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IvAutopilot() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ApRegistry.BLOCKS.register(modBus);
        ApRegistry.ITEMS.register(modBus);
        ApRegistry.BLOCK_ENTITIES.register(modBus);
        ApRegistry.TABS.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ApConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ivautopilot.track.TrackerClientConfig.SPEC);
        ApNetwork.init();
        ChunkKeeper.init();
    }
}
