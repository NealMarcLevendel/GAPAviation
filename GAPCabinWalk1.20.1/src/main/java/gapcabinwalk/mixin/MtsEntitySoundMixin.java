package gapcabinwalk.mixin;

import gapcabinwalk.aboard.AboardClient;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MTS picks interior vs exterior sounds (and muffles the outside world) based on the seat the
 * player is riding. While walking around aboard an aircraft, the player rides no seat, so MTS would
 * play the exterior soundscape. Report the aircraft we're aboard as what we're "riding" for sound.
 */
@Mixin(targets = "minecrafttransportsimulator.entities.components.AEntityD_Definable", remap = false)
public abstract class MtsEntitySoundMixin {
    @Redirect(method = "updateSounds", remap = false, require = 0,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lminecrafttransportsimulator/mcinterface/IWrapperPlayer;getEntityRiding()Lminecrafttransportsimulator/entities/components/AEntityB_Existing;"))
    private AEntityB_Existing gapcabinwalk$aboardCountsAsInside(IWrapperPlayer player) {
        AEntityB_Existing riding = player.getEntityRiding();
        return riding != null ? riding : AboardClient.aboardVehicleForSound();
    }
}
