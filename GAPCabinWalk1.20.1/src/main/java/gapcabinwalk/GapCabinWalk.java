package gapcabinwalk;

import gapcabinwalk.aboard.AboardNetwork;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * GAP Cabin Walk - an addon for Immersive Vehicles and the Golden Aerotransport Partnership pack.
 *
 * Get up from your seat (Shift) and walk around inside an aircraft, on the ground or in flight,
 * the way Create: Aeronautics / Valkyrien Skies ships work: you stay a normal player in the real
 * world, are carried along by the aircraft, and collide with its interior in the aircraft's own
 * frame (see the gapcabinwalk.aboard package).
 *
 * Which aircraft support this is decided by cabin maps in assets/gapcabinwalk/cabins/<packID>/,
 * generated from each aircraft's model by tools/gen_cabin_maps.py.
 */
@Mod(GapCabinWalk.MODID)
public class GapCabinWalk {
    public static final String MODID = "gapcabinwalk";

    public GapCabinWalk() {
        AboardNetwork.init();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, gapcabinwalk.feel.HeadInertiaConfig.SPEC);
    }
}
