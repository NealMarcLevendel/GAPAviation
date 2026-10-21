package gapcabinwalk.aboard;

import gapcabinwalk.GapCabinWalk;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client side:
 *  - CARRY the local player with their aircraft at the start of each of their ticks;
 *  - RECORD their aircraft-relative position after each tick (and detect boarding/leaving);
 *  - place OTHER aboard players from the server's aircraft-relative positions;
 *  - make everyone's legs animate from movement relative to the aircraft, not the aircraft's speed.
 */
@Mod.EventBusSubscriber(modid = GapCabinWalk.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AboardClient {
    /**
     * Ticks left during which the local player is held at the spot they stood up at (in the aircraft's
     * frame), so the dismount and teleport packets that follow - computed from the server's copy of
     * the aircraft, or MTS's own exit point - can't move them into the floor, a wall or out of the hull.
     */
    private static int pinTicks;

    static void onStandUp(AboardNetwork.StandUp msg) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        EntityVehicleF_Physics vehicle = Aboard.find(player.level(), msg.vehicleId());
        if (vehicle == null) {
            return;
        }
        Aboard.State state = new Aboard.State();
        state.vehicleId = msg.vehicleId();
        state.lx = msg.x();
        state.ly = msg.y();
        state.lz = msg.z();
        state.vehicleYaw = Aboard.yaw(vehicle);
        Aboard.CLIENT.put(player.getUUID(), state);
        if (!player.isPassenger()) {
            Point3D world = Aboard.toWorld(vehicle, state.lx, state.ly, state.lz);
            player.setPos(world.x, world.y, world.z);
            player.setDeltaMovement(Vec3.ZERO);
        }
        pinTicks = 12; // about 6 ticks after the dismount arrives (it can lag the stand-up message)
    }

    /** Other players, by entity id, as last reported by the server. */
    private static final Map<Integer, Aboard.State> REMOTE = new HashMap<>();

    static void onSync(AboardNetwork.Sync msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && msg.entityId() == mc.player.getId()) {
            return; // we simulate ourselves
        }
        if (msg.vehicleId() == null) {
            REMOTE.remove(msg.entityId());
            return;
        }
        Aboard.State state = REMOTE.computeIfAbsent(msg.entityId(), k -> new Aboard.State());
        state.vehicleId = msg.vehicleId();
        state.lx = msg.x();
        state.ly = msg.y();
        state.lz = msg.z();
        state.age = 0;
    }

    /** The aircraft the local player is walking around in (not seated), for MTS's sound logic; else null. */
    @javax.annotation.Nullable
    public static EntityVehicleF_Physics aboardVehicleForSound() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isPassenger()) {
            return null;
        }
        Aboard.State state = Aboard.CLIENT.get(player.getUUID());
        return state != null ? Aboard.find(player.level(), state.vehicleId) : null;
    }

    /** Start of the local player's tick: move them with the aircraft since last tick. */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.level().isClientSide || !(entity instanceof LocalPlayer player)) {
            return;
        }
        Aboard.State state = Aboard.CLIENT.get(player.getUUID());
        if (state == null || player.isPassenger()) {
            return;
        }
        EntityVehicleF_Physics vehicle = Aboard.find(player.level(), state.vehicleId);
        if (vehicle == null) {
            if (Aboard.CLIENT.remove(player.getUUID()) != null) {
                AboardNetwork.CHANNEL.sendToServer(new AboardNetwork.Report(null, 0, 0, 0));
            }
            return;
        }
        // Same spot in the aircraft's frame as at the end of last tick (overrides any push from MTS).
        Point3D world = Aboard.toWorld(vehicle, state.lx, state.ly, state.lz);
        player.setPos(world.x, world.y, world.z);
        // Turn with the aircraft (MTS yaw is the negative of Minecraft yaw).
        float dYaw = (float) -(Aboard.yaw(vehicle) - state.vehicleYaw);
        if (dYaw != 0) {
            player.setYRot(player.getYRot() + dYaw);
            player.yHeadRot += dYaw;
            player.yBodyRot += dYaw;
            Vec3 v = player.getDeltaMovement();
            double r = Math.toRadians(dYaw), cos = Math.cos(r), sin = Math.sin(r);
            player.setDeltaMovement(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos);
        }
        player.fallDistance = 0;
    }

    /** End of the local player's tick: are we aboard, and where in the aircraft are we now? */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !event.player.level().isClientSide || !(event.player instanceof LocalPlayer player)) {
            return;
        }
        Aboard.State state = Aboard.CLIENT.get(player.getUUID());
        if (pinTicks > 0) {
            pinTicks--; // counts down even while the dismount hasn't reached us yet
        }
        if (pinTicks > 0 && state != null && player.isPassenger()) {
            return; // stood up on the server, dismount not here yet: keep the stand-up spot waiting
        }
        if (pinTicks > 0 && state != null && !player.isPassenger()) {
            // Just stood up: stay exactly at the stand-up spot in the aircraft's frame for a few ticks.
            EntityVehicleF_Physics pinned = Aboard.find(player.level(), state.vehicleId);
            if (pinned != null) {
                Point3D world = Aboard.toWorld(pinned, state.lx, state.ly, state.lz);
                player.setPos(world.x, world.y, world.z);
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0;
                state.vehicleYaw = Aboard.yaw(pinned);
                AboardNetwork.CHANNEL.sendToServer(new AboardNetwork.Report(pinned.uniqueUUID, (float) state.lx, (float) state.ly, (float) state.lz));
                return;
            }
        }
        EntityVehicleF_Physics vehicle = null;
        if (!player.isPassenger() && !player.isSpectator()) {
            vehicle = state != null ? Aboard.find(player.level(), state.vehicleId) : null;
            if (vehicle == null || !Aboard.contains(vehicle, player.getX(), player.getY(), player.getZ())) {
                vehicle = Aboard.findContaining(player.level(), player.getX(), player.getY(), player.getZ());
            }
        }
        if (vehicle == null) {
            if (Aboard.CLIENT.remove(player.getUUID()) != null) {
                AboardNetwork.CHANNEL.sendToServer(new AboardNetwork.Report(null, 0, 0, 0));
            }
            return;
        }
        if (state == null || !vehicle.uniqueUUID.equals(state.vehicleId)) {
            state = new Aboard.State();
            Aboard.CLIENT.put(player.getUUID(), state);
        }
        Point3D local = Aboard.toLocal(vehicle, player.getX(), player.getY(), player.getZ());
        state.vehicleId = vehicle.uniqueUUID;
        state.lx = local.x;
        state.ly = local.y;
        state.lz = local.z;
        state.vehicleYaw = Aboard.yaw(vehicle);
        animateLegs(player, state);
        // Tell the server where we are aboard; it trusts this rather than its own copy of the aircraft.
        AboardNetwork.CHANNEL.sendToServer(new AboardNetwork.Report(vehicle.uniqueUUID, (float) local.x, (float) local.y, (float) local.z));
    }

    /** After everything has ticked: place other aboard players on their aircraft and fix their legs. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || mc.level == null || mc.isPaused()) {
            return;
        }
        Iterator<Map.Entry<Integer, Aboard.State>> it = REMOTE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Aboard.State> entry = it.next();
            Aboard.State state = entry.getValue();
            Entity entity = mc.level.getEntity(entry.getKey());
            EntityVehicleF_Physics vehicle = Aboard.find(mc.level, state.vehicleId);
            if (!(entity instanceof AbstractClientPlayer other) || entity == mc.player || vehicle == null || ++state.age > 40) {
                it.remove();
                continue;
            }
            Point3D world = Aboard.toWorld(vehicle, state.lx, state.ly, state.lz);
            other.setPos(world.x, world.y, world.z);
            // Stop vanilla's interpolation dragging them toward an older (pre-move) position.
            other.lerpTo(world.x, world.y, world.z, other.getYRot(), other.getXRot(), 1, false);
            animateLegs(other, state);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            REMOTE.clear();
            Aboard.CLIENT.clear();
        }
    }

    /**
     * Vanilla animates legs from world movement, which aboard a flying aircraft is the aircraft's
     * speed (legs would flail at full speed). Keep our own walk-animation state from movement
     * RELATIVE to the aircraft, computed like LivingEntity.updateWalkAnimation, and write it over
     * whatever vanilla did this tick.
     */
    private static void animateLegs(LivingEntity entity, Aboard.State state) {
        WalkAnimationState walk = entity.walkAnimation;
        if (!state.animInit) {
            state.animInit = true;
            state.animLx = state.lx;
            state.animLz = state.lz;
            state.animSpeed = 0;
            state.animPos = walk.position();
        }
        double moved = Math.sqrt(Mth.square(state.lx - state.animLx) + Mth.square(state.lz - state.animLz));
        state.animLx = state.lx;
        state.animLz = state.lz;
        float target = Math.min((float) moved * 4.0F, 1.0F);
        float prevSpeed = state.animSpeed;
        state.animSpeed += (target - state.animSpeed) * 0.4F;
        state.animPos += state.animSpeed;
        // Force (speedOld, speed, position) using only the public API:
        // update(t, 1) sets speedOld = speed, speed = t, position += t.
        walk.setSpeed(prevSpeed);
        walk.update(state.animPos - walk.position(), 1.0F);
        walk.setSpeed(state.animSpeed);
    }

    private AboardClient() {
    }
}
