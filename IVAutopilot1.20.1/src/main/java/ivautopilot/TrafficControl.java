package ivautopilot;

import ivautopilot.nav.FlightPlan;
import ivautopilot.nav.Runway;
import mcinterface1201.WrapperWorld;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Traffic control (stage 2): keeps autopilot aircraft from running into each other.
 *
 * Runways: one aircraft at a time holds a runway (departing: from holding short until airborne past
 * the end; arriving: from the approach until it has left the runway). A runway also counts as busy
 * while any other aircraft is physically on it. Arrivals queue in order of reaching the circuit and
 * have priority over departures once on approach; arrivals that find the runway busy fly another
 * circuit, stacked 30 blocks apart.
 *
 * Taxiways: a taxiing aircraft holds the waypoints just ahead of it; others stop short of them.
 *
 * Everything here is in memory (rebuilt as aircraft fly); stale holds are dropped every half second.
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class TrafficControl {
    private static final Map<String, UUID> RUNWAY_HOLDER = new HashMap<>();
    private static final Map<String, List<UUID>> ARRIVALS = new HashMap<>();
    private static final Map<String, Map<BlockPos, UUID>> NODES = new HashMap<>();

    private TrafficControl() {
    }

    static String key(ServerLevel level, Runway runway) {
        return key(level.dimension(), runway);
    }

    static String key(ResourceKey<Level> dimension, Runway runway) {
        return dimension.location() + "|" + runway.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------------------------------------
    // Runways
    // ---------------------------------------------------------------------------------------------

    /** On the runway strip: on (or just above) the runway, within 20 blocks of its centerline and ends. */
    public static boolean onStrip(EntityVehicleF_Physics aircraft, Runway runway) {
        Vec3 dir = Runway.heading(runway.a(), runway.b());
        double ax = aircraft.position.x - runway.a().x, az = aircraft.position.z - runway.a().z;
        double along = ax * dir.x + az * dir.z;
        double cross = Math.abs(ax * dir.z - az * dir.x);
        double low = Math.min(runway.a().y, runway.b().y);
        return along > -20 && along < runway.length() + 20 && cross < 20 && aircraft.position.y < low + 15;
    }

    /** Another aircraft physically on the runway (any aircraft, autopilot or not), or null. */
    @Nullable
    static EntityVehicleF_Physics occupant(ServerLevel level, Runway runway, UUID self) {
        for (EntityVehicleF_Physics other : WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class)) {
            if (other.isValid && !other.uniqueUUID.equals(self) && other.definition.motorized != null
                    && other.definition.motorized.isAircraft && onStrip(other, runway)) {
                return other;
            }
        }
        return null;
    }

    /** How far `other` is in front of `me` (along my heading; negative = behind). */
    static double ahead(EntityVehicleF_Physics me, EntityVehicleF_Physics other) {
        double yaw = Math.toRadians(ApServer.headingOf(me));
        return (other.position.x - me.position.x) * -Math.sin(yaw) + (other.position.z - me.position.z) * Math.cos(yaw);
    }

    /** Another aircraft on the runway in front of (or beside) us, or null. */
    @Nullable
    static EntityVehicleF_Physics occupantAhead(ServerLevel level, Runway runway, EntityVehicleF_Physics me) {
        for (EntityVehicleF_Physics other : WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class)) {
            if (other != me && other.isValid && other.definition.motorized != null && other.definition.motorized.isAircraft
                    && onStrip(other, runway) && ahead(me, other) > -5) {
                return other;
            }
        }
        return null;
    }

    static boolean holds(ServerLevel level, Runway runway, UUID self) {
        return self.equals(RUNWAY_HOLDER.get(key(level, runway)));
    }

    /**
     * Ask for the runway. Granted if nobody else holds it, nobody else is on it, and (arrivals) we're
     * first in the arrival queue or (departures) no arrival is on approach or final for it.
     */
    static boolean request(ServerLevel level, Runway runway, UUID self, boolean arrival) {
        if (!available(level, runway, self, arrival)) {
            return false;
        }
        String key = key(level, runway);
        RUNWAY_HOLDER.put(key, self);
        List<UUID> queue = ARRIVALS.get(key);
        if (queue != null) {
            queue.remove(self);
        }
        return true;
    }

    /** Whether request() would be granted right now (without taking the runway). */
    static boolean available(ServerLevel level, Runway runway, UUID self, boolean arrival) {
        String key = key(level, runway);
        UUID holder = RUNWAY_HOLDER.get(key);
        EntityVehicleF_Physics me = ApServer.find(level, self);
        if (!arrival && me != null && onStrip(me, runway)) {
            // Departing, already on the runway: never with anything in front of us; and whoever is
            // physically first in line goes first (a departure holding it from behind us waits).
            if (occupantAhead(level, runway, me) != null) {
                return false;
            }
            if (holder == null || holder.equals(self)) {
                return true; // lined up and clear ahead: go, rather than block arrivals waiting for it
            }
            EntityVehicleF_Physics h = ApServer.find(level, holder);
            return h != null && onStrip(h, runway) && ahead(me, h) < -5 && h.indicatedSpeed < 5 && ApServer.isDeparture(holder);
        }
        if (self.equals(holder)) {
            return true;
        }
        if (holder != null || occupant(level, runway, self) != null) {
            return false;
        }
        List<UUID> queue = ARRIVALS.getOrDefault(key, List.of());
        if (arrival) {
            return queue.isEmpty() || queue.get(0).equals(self);
        }
        for (UUID a : queue) {
            if (ApServer.arrivalImminent(a)) {
                return false; // arrivals first: an aircraft is about to land or waiting to
            }
        }
        return true;
    }

    /** Who holds the runway (for status lines), or null. */
    @Nullable
    static UUID holder(ServerLevel level, Runway runway) {
        return RUNWAY_HOLDER.get(key(level, runway));
    }

    static void releaseRunway(UUID self) {
        RUNWAY_HOLDER.values().removeIf(self::equals);
    }

    /** Join (if not already in) the arrival queue for this runway. Returns our place: 0 = next to land. */
    static int arrivalPosition(ServerLevel level, Runway runway, UUID self) {
        List<UUID> queue = ARRIVALS.computeIfAbsent(key(level, runway), k -> new ArrayList<>());
        if (!queue.contains(self)) {
            queue.add(self);
        }
        return queue.indexOf(self);
    }

    // ---------------------------------------------------------------------------------------------
    // Taxiway waypoints
    // ---------------------------------------------------------------------------------------------

    @Nullable
    static UUID nodeHolder(ServerLevel level, BlockPos node) {
        Map<BlockPos, UUID> map = NODES.get(level.dimension().location().toString());
        return map == null ? null : map.get(node);
    }

    /** Try to hold a waypoint. False if another aircraft holds it. */
    static boolean holdNode(ServerLevel level, BlockPos node, UUID self) {
        Map<BlockPos, UUID> map = NODES.computeIfAbsent(level.dimension().location().toString(), k -> new HashMap<>());
        UUID holder = map.get(node);
        if (holder != null && !holder.equals(self)) {
            return false;
        }
        map.put(node, self);
        return true;
    }

    /** Release our waypoints except the ones given. */
    static void releaseNodesExcept(ServerLevel level, UUID self, Set<BlockPos> keep) {
        Map<BlockPos, UUID> map = NODES.get(level.dimension().location().toString());
        if (map != null) {
            map.entrySet().removeIf(e -> e.getValue().equals(self) && !keep.contains(e.getKey()));
        }
    }

    /** Waypoints held by other aircraft (for routing around them). */
    static Set<BlockPos> nodesHeldByOthers(ServerLevel level, UUID self) {
        Set<BlockPos> out = new HashSet<>();
        Map<BlockPos, UUID> map = NODES.get(level.dimension().location().toString());
        if (map != null) {
            map.forEach((pos, id) -> {
                if (!id.equals(self)) {
                    out.add(pos);
                }
            });
        }
        return out;
    }

    static void releaseAll(UUID self) {
        releaseRunway(self);
        ARRIVALS.values().forEach(q -> q.remove(self));
        NODES.values().forEach(m -> m.values().removeIf(self::equals));
    }

    // ---------------------------------------------------------------------------------------------
    // Housekeeping
    // ---------------------------------------------------------------------------------------------

    /** Drop holds and queue places of aircraft that no longer need them. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        for (Map.Entry<String, UUID> e : new ArrayList<>(RUNWAY_HOLDER.entrySet())) {
            if (!ApServer.stillNeedsRunway(e.getValue(), e.getKey())) {
                RUNWAY_HOLDER.remove(e.getKey());
            }
        }
        ARRIVALS.forEach((key, queue) -> queue.removeIf(id -> !ApServer.isArriving(id, key)));
        NODES.values().forEach(m -> m.values().removeIf(id -> !ApServer.isTaxiing(id)));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RUNWAY_HOLDER.clear();
        ARRIVALS.clear();
        NODES.clear();
    }
}
