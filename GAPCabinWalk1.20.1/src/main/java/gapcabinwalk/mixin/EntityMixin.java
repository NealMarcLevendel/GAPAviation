package gapcabinwalk.mixin;

import gapcabinwalk.aboard.AboardPhysics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While aboard an aircraft, the local player's collision is done in the aircraft's frame
 * (see AboardPhysics). Returning early also skips MTS's own world-space box collision, which
 * can't represent a rotated aircraft.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void gapcabinwalk$collideAboard(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 result = AboardPhysics.collide((Entity) (Object) this, movement);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
