package gapcabinwalk.mixin;

import gapcabinwalk.aboard.Aboard;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * MTS moves entities standing on a vehicle itself, by teleporting them whenever the vehicle moved
 * more than 0.25 blocks in a tick (i.e. every tick in flight). Players that we carry must be left
 * out of that, or they'd be rubber-banded every tick.
 */
@Mixin(targets = "minecrafttransportsimulator.entities.instances.AEntityVehicleD_Moving", remap = false)
public abstract class MtsVehicleCarryMixin {
    @Redirect(method = "doPostUpdateLogic", remap = false, require = 0,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lminecrafttransportsimulator/mcinterface/AWrapperWorld;getEntitiesWithin(Lminecrafttransportsimulator/baseclasses/BoundingBox;)Ljava/util/List;"))
    private List<IWrapperEntity> gapcabinwalk$skipAboardPlayers(AWrapperWorld world, BoundingBox box) {
        List<IWrapperEntity> entities = new ArrayList<>(world.getEntitiesWithin(box));
        boolean client = world.isClient();
        entities.removeIf(entity -> Aboard.isAboard(client, entity.getID()));
        return entities;
    }
}
