package gapcabinwalk.mixin;

import gapcabinwalk.aboard.AboardClient;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MTS skips the Doppler effect for the vehicle the player is riding. Do the same for the aircraft
 * we're walking around in, or its own engines would be pitch-shifted as if it were flying past us.
 */
@Mixin(targets = "mcinterface1201.InterfaceSound", remap = false)
public abstract class MtsSoundDopplerMixin {
    @Redirect(method = "update", remap = false, require = 0,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lminecrafttransportsimulator/mcinterface/IWrapperPlayer;getEntityRiding()Lminecrafttransportsimulator/entities/components/AEntityB_Existing;"))
    private static AEntityB_Existing gapcabinwalk$aboardNoDoppler(IWrapperPlayer player) {
        AEntityB_Existing riding = player.getEntityRiding();
        return riding != null ? riding : AboardClient.aboardVehicleForSound();
    }
}
