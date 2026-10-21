package gapcabinwalk.aboard;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Tiny client-only helper; only ever called when level().isClientSide. */
final class AboardClientHooks {
    static boolean isLocalPlayer(Entity entity) {
        return entity != null && entity == Minecraft.getInstance().player;
    }

    private AboardClientHooks() {
    }
}
