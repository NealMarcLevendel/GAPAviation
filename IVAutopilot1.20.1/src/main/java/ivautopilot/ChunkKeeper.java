package ivautopilot;

import mcinterface1201.WrapperWorld;
import minecrafttransportsimulator.entities.components.AEntityA_Base;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartSeat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps aircraft in loaded, ticking chunks.
 *
 * Minecraft stops ticking (and stops showing to clients) any non-player entity that moves into a
 * chunk that isn't fully loaded. A fast aircraft over new terrain outruns chunk generation, so it
 * would fly into chunks that don't exist yet, freeze and vanish - and its riders fall out of the sky.
 * So for every aircraft that is flying with someone aboard, or has the autopilot engaged, we hold
 * forced chunk tickets: 3x3 ticking chunks under it, and a loaded-only corridor one chunk wide along
 * its track for the next few seconds, so terrain is generated before it gets there. (A first version
 * ticked a 3-wide, 8-second corridor - hundreds of chunks - and overloaded the server.)
 * With the autopilot engaged this also keeps an empty aircraft flying to its destination when
 * nobody is anywhere near it.
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class ChunkKeeper {
    private static final int AROUND = 1; // 3 x 3 ticking chunks under the aircraft
    private static final Map<UUID, Held> HELD = new HashMap<>();

    private record Held(ResourceKey<Level> dimension, Set<Long> ticking, Set<Long> loaded) {
    }

    private ChunkKeeper() {
    }

    /** Called at mod construction: tickets from a previous session are stale. */
    static void init() {
        ForgeChunkManager.setForcedChunkLoadingCallback(IvAutopilot.MODID, (level, helper) ->
                new ArrayList<>(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 5 != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (EntityVehicleF_Physics aircraft : new ArrayList<>(WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class))) {
                if (!aircraft.isValid || aircraft.definition.motorized == null || !aircraft.definition.motorized.isAircraft) {
                    continue;
                }
                double vx = (aircraft.position.x - aircraft.prevPosition.x) * 20, vz = (aircraft.position.z - aircraft.prevPosition.z) * 20;
                double speed = Math.sqrt(vx * vx + vz * vz);
                boolean autopilot = ApServer.isEngaged(aircraft.uniqueUUID);
                boolean pilotedAndMoving = speed > ApConfig.CHUNK_MIN_SPEED.get() && hasRider(aircraft) && ApConfig.LOAD_FOR_PILOTED.get();
                if (!autopilot && !pilotedAndMoving) {
                    continue;
                }
                seen.add(aircraft.uniqueUUID);
                keep(level, aircraft.uniqueUUID, wanted(aircraft.position.x, aircraft.position.z, vx, vz));
            }
        }
        for (UUID id : new ArrayList<>(HELD.keySet())) {
            if (!seen.contains(id)) {
                release(server, id);
            }
        }
    }

    private static boolean hasRider(EntityVehicleF_Physics aircraft) {
        for (Object part : aircraft.allParts) {
            if (part instanceof PartSeat seat && seat.rider != null) {
                return true;
            }
        }
        return false;
    }

    /** Chunks to hold for one aircraft: a few ticking ones under it, and a loaded-only corridor ahead. */
    private record Want(Set<Long> ticking, Set<Long> loaded) {
    }

    /**
     * 3x3 TICKING chunks under the aircraft (all an entity needs to keep running), plus a corridor
     * one chunk wide along its track for the next few seconds that is only LOADED (so it is generated
     * in time, without the cost of ticking it).
     */
    private static Want wanted(double x, double z, double vx, double vz) {
        Set<Long> ticking = new HashSet<>(), loaded = new HashSet<>();
        int cx = ((int) Math.floor(x)) >> 4, cz = ((int) Math.floor(z)) >> 4;
        for (int dx = -AROUND; dx <= AROUND; dx++) {
            for (int dz = -AROUND; dz <= AROUND; dz++) {
                ticking.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
        double speed = Math.sqrt(vx * vx + vz * vz);
        double ahead = Math.min(ApConfig.CHUNK_LOOKAHEAD_SECONDS.get() * speed, 600);
        if (ahead > 16) {
            double ux = vx / speed, uz = vz / speed;
            for (double d = 16; d <= ahead; d += 12) {
                long chunk = ChunkPos.asLong(((int) Math.floor(x + ux * d)) >> 4, ((int) Math.floor(z + uz * d)) >> 4);
                if (!ticking.contains(chunk)) {
                    loaded.add(chunk);
                }
            }
        }
        return new Want(ticking, loaded);
    }

    /** At most this many new chunk tickets per aircraft per update (every 5 ticks), so turns don't cause bursts. */
    private static final int MAX_NEW_PER_UPDATE = 10;

    private static void keep(ServerLevel level, UUID aircraft, Want want) {
        Held held = HELD.get(aircraft);
        if (held != null && !held.dimension().equals(level.dimension())) {
            release(level.getServer(), aircraft); // changed dimension
            held = null;
        }
        Set<Long> haveTicking = held == null ? new HashSet<>() : new HashSet<>(held.ticking());
        Set<Long> haveLoaded = held == null ? new HashSet<>() : new HashSet<>(held.loaded());
        update(level, aircraft, haveTicking, want.ticking(), true, Integer.MAX_VALUE); // under the aircraft: always, right away
        update(level, aircraft, haveLoaded, want.loaded(), false, MAX_NEW_PER_UPDATE);
        HELD.put(aircraft, new Held(level.dimension(), haveTicking, haveLoaded));
    }

    /** Bring the held set toward the wanted one: drop what's no longer wanted, add up to maxNew new ones. */
    private static void update(ServerLevel level, UUID aircraft, Set<Long> have, Set<Long> want, boolean ticking, int maxNew) {
        for (long chunk : new ArrayList<>(have)) {
            if (!want.contains(chunk)) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, ticking);
                have.remove(chunk);
            }
        }
        int added = 0;
        for (long chunk : want) {
            if (added >= maxNew) {
                break;
            }
            if (have.add(chunk)) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true, ticking);
                added++;
            }
        }
    }

    private static void release(MinecraftServer server, UUID aircraft) {
        Held held = HELD.remove(aircraft);
        ServerLevel level = held == null ? null : server.getLevel(held.dimension());
        if (level != null) {
            for (long chunk : held.ticking()) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, true);
            }
            for (long chunk : held.loaded()) {
                ForgeChunkManager.forceChunk(level, IvAutopilot.MODID, aircraft, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, false);
            }
        }
    }

    static void clear() {
        HELD.clear();
    }
}
