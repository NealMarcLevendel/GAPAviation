package ivautopilot.nav;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Terrain heights for route planning and the terrain guard.
 *
 *  - generated(): what the world generator says the ground height is at (x, z). Works anywhere,
 *    never loads or generates chunks, safe to call off the main thread. Doesn't know about
 *    player builds or trees, so the planner adds clearance on top.
 *  - actual(): the real top block, only where the chunk is already loaded (main thread only).
 */
public final class Terrain implements RoutePlanner.HeightSource {
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;

    public Terrain(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
    }

    @Override
    public int generated(int x, int z) {
        try {
            return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        } catch (Exception e) {
            return level.getSeaLevel();
        }
    }

    /** Real surface height if the chunk is loaded, else Integer.MIN_VALUE. Main thread only. */
    public int actual(int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    public static boolean loaded(ServerLevel level, double x, double z) {
        return level.hasChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }
}
