package ivautopilot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the chunks around an aircraft loaded (and ticking) while its autopilot is engaged, so it
 * keeps flying when nobody is near it - e.g. the pilot got out at the airport, or is far behind.
 */
final class ChunkKeeper {
    private static final int RADIUS = 2; // 5 x 5 chunks
    private static final Map<UUID, Set<Long>> HELD = new HashMap<>();

    private ChunkKeeper() {
    }

    /** Called at mod construction: tickets from a previous session are stale (autopilot state isn't saved). */
    static void init() {
        ForgeChunkManager.setForcedChunkLoadingCallback(IvAutopilot.MODID, (level, helper) ->
                new ArrayList<>(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));
    }

    static void keep(ServerLevel level, UUID aircraft, double x, double z) {
        int cx = ((int) Math.floor(x)) >> 4, cz = ((int) Math.floor(z)) >> 4;
        Set<Long> want = new HashSet<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                want.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
        Set<Long> held = HELD.computeIfAbsent(aircraft, k -> new HashSet<>());
        if (held.equals(want)) {
            return;
        }
        for (long chunk : held) {
            if (!want.contains(chunk)) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, true);
            }
        }
        for (long chunk : want) {
            if (!held.contains(chunk)) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true, true);
            }
        }
        HELD.put(aircraft, want);
    }

    static void release(ServerLevel level, UUID aircraft) {
        Set<Long> held = HELD.remove(aircraft);
        if (held != null) {
            for (long chunk : held) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, true);
            }
        }
    }

    static boolean holds(UUID aircraft) {
        return HELD.containsKey(aircraft);
    }

    static void clear() {
        HELD.clear();
    }
}
