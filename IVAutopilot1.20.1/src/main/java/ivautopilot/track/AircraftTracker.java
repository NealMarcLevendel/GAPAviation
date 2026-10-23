package ivautopilot.track;

import ivautopilot.ApConfig;
import ivautopilot.ApNetwork;
import ivautopilot.ApServer;
import ivautopilot.IvAutopilot;
import mcinterface1201.WrapperWorld;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plane tracker, server side. Once a second, sends every player a list of all aircraft in the world
 * with their autopilot state and planned path - including ones far beyond the player's view - so a
 * map mod (JourneyMap, see compat.journeymap) can show them. Aircraft whose chunks are unloaded
 * (parked somewhere nobody is) are remembered where they were last seen, saved with the world.
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AircraftTracker {
    private AircraftTracker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = event.getServer();
        if (event.phase != TickEvent.Phase.END || server.getTickCount() % 20 != 0 || !ApConfig.TRACKER_ENABLED.get()
                || server.getPlayerList().getPlayerCount() == 0) {
            return;
        }
        LastSeen lastSeen = LastSeen.get(server);
        Map<UUID, TrackedAircraft> all = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (EntityVehicleF_Physics aircraft : new ArrayList<>(WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class))) {
                if (aircraft.isValid && aircraft.definition.motorized != null && aircraft.definition.motorized.isAircraft) {
                    TrackedAircraft info = ApServer.trackInfo(level, aircraft);
                    all.put(info.id(), info);
                    lastSeen.seen(info);
                }
            }
        }
        lastSeen.forget(server, all.keySet());
        for (TrackedAircraft remembered : lastSeen.entries()) {
            all.putIfAbsent(remembered.id(), remembered);
        }
        ApNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new ApNetwork.Tracker(new ArrayList<>(all.values())));
    }

    /** Where unloaded aircraft were last seen. */
    static final class LastSeen extends SavedData {
        private static final String ID = "ivautopilot_tracker";
        private final Map<UUID, TrackedAircraft> entries = new LinkedHashMap<>();
        /** Consecutive updates an aircraft was missing although its chunk is loaded and ticking. */
        private final Map<UUID, Integer> missing = new HashMap<>();

        static LastSeen get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(LastSeen::load, LastSeen::new, ID);
        }

        void seen(TrackedAircraft info) {
            entries.put(info.id(), new TrackedAircraft(info.id(), info.name(), info.dimension(), info.x(), info.y(), info.z(), info.bearing(),
                    0, "PARKED", "", "", -1, false, new float[0], new float[0]));
            missing.remove(info.id());
            setDirty();
        }

        /** Drop remembered aircraft whose spot is loaded and ticking but which aren't there (destroyed / picked up). */
        void forget(MinecraftServer server, Set<UUID> live) {
            for (TrackedAircraft entry : new ArrayList<>(entries.values())) {
                if (live.contains(entry.id())) {
                    continue;
                }
                ServerLevel level = null;
                for (ServerLevel l : server.getAllLevels()) {
                    if (l.dimension().location().toString().equals(entry.dimension())) {
                        level = l;
                    }
                }
                BlockPos pos = BlockPos.containing(entry.x(), Math.max(level == null ? 0 : level.getMinBuildHeight(), Math.min(entry.y(), 300)), entry.z());
                if (level == null || level.isPositionEntityTicking(pos)) {
                    int count = missing.merge(entry.id(), 1, Integer::sum);
                    if (count >= 5) {
                        entries.remove(entry.id());
                        missing.remove(entry.id());
                        setDirty();
                    }
                }
            }
        }

        List<TrackedAircraft> entries() {
            return new ArrayList<>(entries.values());
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (TrackedAircraft a : entries.values()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("id", a.id());
                t.putString("name", a.name());
                t.putString("dim", a.dimension());
                t.putFloat("x", a.x());
                t.putFloat("y", a.y());
                t.putFloat("z", a.z());
                t.putFloat("bearing", a.bearing());
                list.add(t);
            }
            tag.put("aircraft", list);
            return tag;
        }

        static LastSeen load(CompoundTag tag) {
            LastSeen data = new LastSeen();
            Set<UUID> ids = new HashSet<>();
            for (Tag t : tag.getList("aircraft", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                UUID id = c.getUUID("id");
                if (ids.add(id)) {
                    data.entries.put(id, new TrackedAircraft(id, c.getString("name"), c.getString("dim"), c.getFloat("x"), c.getFloat("y"),
                            c.getFloat("z"), c.getFloat("bearing"), 0, "PARKED", "", "", -1, false, new float[0], new float[0]));
                }
            }
            return data;
        }
    }
}
