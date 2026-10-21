package gapcabinwalk.mixin;

import com.seibel.distanthorizons.lwjgl.ILWJGLService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes Distant Horizons crashing the game on macOS (Apple GL-over-Metal driver).
 *
 * Every frame DH saves the GL state, reading GL_POLYGON_MODE with LWJGL's one-int glGetInteger,
 * which reads into a 4-byte slot on LWJGL's MemoryStack. Apple's driver writes TWO ints for
 * GL_POLYGON_MODE (front and back), so 4 bytes past the end of that slot get overwritten with
 * GL_FILL (0x1B02) every frame. That corrupts native memory and the game dies a few seconds later in
 * an unrelated place - typically SIGSEGV in objc_msgSend under IOGPUMetalCommandBufferStorageDealloc,
 * at an address like 0x1_0000_1b0x. Read it into a 4-int array instead.
 *
 * Diagnosis credit: bh679/dungeon-train-mc PR #1571. Only applied when DH is installed (@Pseudo,
 * optional mixin config gapcabinwalk.dh.mixins.json).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.GLState", remap = false)
public class DhGlStateMixin {
    private static final int GL_POLYGON_MODE = 0x0B40;

    @Redirect(method = "saveState", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/lwjgl/ILWJGLService;glGetInteger(I)I", remap = false))
    private int gapcabinwalk$safeGetInteger(ILWJGLService service, int pname) {
        if (pname == GL_POLYGON_MODE) {
            int[] values = new int[4];
            service.glGetIntegerv(pname, values); // array form: room for the driver's second value
            return values[0];
        }
        return service.glGetInteger(pname);
    }
}
