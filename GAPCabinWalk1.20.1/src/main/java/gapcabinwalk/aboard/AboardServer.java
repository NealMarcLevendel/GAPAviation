package gapcabinwalk.aboard;

import gapcabinwalk.GapCabinWalk;
import gapcabinwalk.cabin.CabinMap;
import mcinterface1201.BuilderEntityLinkedSeat;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartSeat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server side:
 *  - Shift in a seat of an aircraft with a cabin: instead of being put outside, you stand up in
 *    the aisle next to your seat (in flight too). Sitting down is MTS's normal right-click.
 *  - Tracks who is aboard (from the positions their clients report) and tells other clients
 *    their aircraft-relative position every tick.
 */
@Mod.EventBusSubscriber(modid = GapCabinWalk.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AboardServer {
    private static final Set<UUID> PENDING_STAND_UP = new LinkedHashSet<>();
    private static boolean ownDismount;

    /** Shift while seated: cancel MTS's dismount (which would put you outside) and stand up inside instead. */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isDismounting() || event.getLevel().isClientSide || ownDismount
                || !(event.getEntityMounting() instanceof Player player) || !player.isAlive() || !player.isShiftKeyDown()) {
            return;
        }
        if (event.getEntityBeingMounted() instanceof BuilderEntityLinkedSeat seatBuilder
                && seatBuilder.entity instanceof PartSeat seat && seat.vehicleOn != null
                && CabinMap.forVehicle(seat.vehicleOn) != null) {
            event.setCanceled(true);
            PENDING_STAND_UP.add(player.getUUID());
        }
    }

    private static void standUp(ServerPlayer player) {
        if (!(player.getVehicle() instanceof BuilderEntityLinkedSeat seatBuilder)
                || !(seatBuilder.entity instanceof PartSeat seat) || seat.vehicleOn == null) {
            return;
        }
        EntityVehicleF_Physics vehicle = seat.vehicleOn;
        CabinMap map = CabinMap.forVehicle(vehicle);
        if (map == null) {
            return;
        }
        // Nearest spot next to the seat where a full-size player fits (crouching height as a fallback).
        double half = player.getBbWidth() / 2;
        double[] spot = Aboard.findStandSpot(vehicle, map, seat.localOffset.x, seat.localOffset.z, 6.0, half, 1.8);
        if (spot == null) {
            spot = Aboard.findStandSpot(vehicle, map, seat.localOffset.x, seat.localOffset.z, 6.0, half, 1.5);
        }
        if (spot == null) {
            player.displayClientMessage(Component.translatable("msg.gapcabinwalk.no_room"), true);
            return;
        }
        Point3D world = Aboard.toWorld(vehicle, spot[0], spot[1] + 0.001, spot[2]);

        ownDismount = true;
        try {
            seat.removeRider(); // MTS: controller count, HUD, doors... (puts the player outside)
        } finally {
            ownDismount = false;
        }
        player.stopRiding();
        player.teleportTo(world.x, world.y, world.z); // ...and we put them in the aisle instead
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0;
        record(player, vehicle);
        // The client places itself from the aircraft-relative spot (its copy of the aircraft may differ from ours).
        AboardNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AboardNetwork.StandUp(vehicle.uniqueUUID, (float) spot[0], (float) (spot[1] + 0.001), (float) spot[2]));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_STAND_UP.isEmpty()) {
            return;
        }
        for (UUID uuid : new ArrayList<>(PENDING_STAND_UP)) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                standUp(player);
            }
        }
        PENDING_STAND_UP.clear();
    }

    /** A client says where it is aboard (or that it left). Sanity-check, store, and pass on to others. */
    static void onReport(ServerPlayer player, AboardNetwork.Report msg) {
        EntityVehicleF_Physics vehicle = msg.vehicleId() != null && !player.isPassenger() && !player.isSpectator()
                ? Aboard.find(player.level(), msg.vehicleId()) : null;
        if (vehicle == null || vehicle.position.distanceTo(new Point3D(player.getX(), player.getY(), player.getZ())) > 64) {
            if (Aboard.SERVER.remove(player.getUUID()) != null) {
                send(player, new AboardNetwork.Sync(player.getId(), null, 0, 0, 0));
            }
            return;
        }
        Aboard.State state = Aboard.SERVER.computeIfAbsent(player.getUUID(), k -> new Aboard.State());
        state.vehicleId = vehicle.uniqueUUID;
        state.lx = msg.x();
        state.ly = msg.y();
        state.lz = msg.z();
        state.vehicleYaw = Aboard.yaw(vehicle);
        state.age = 0;
        send(player, new AboardNetwork.Sync(player.getId(), vehicle.uniqueUUID, msg.x(), msg.y(), msg.z()));
    }

    /** Drop players whose client stopped reporting (or who sat down / died). */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        Aboard.State state = Aboard.SERVER.get(player.getUUID());
        if (state != null && (player.isPassenger() || !player.isAlive() || ++state.age > 20)) {
            Aboard.SERVER.remove(player.getUUID());
            send(player, new AboardNetwork.Sync(player.getId(), null, 0, 0, 0));
        }
    }

    private static void record(ServerPlayer player, EntityVehicleF_Physics vehicle) {
        Aboard.State state = Aboard.SERVER.computeIfAbsent(player.getUUID(), k -> new Aboard.State());
        Point3D local = Aboard.toLocal(vehicle, player.getX(), player.getY(), player.getZ());
        state.vehicleId = vehicle.uniqueUUID;
        state.lx = local.x;
        state.ly = local.y;
        state.lz = local.z;
        state.vehicleYaw = Aboard.yaw(vehicle);
        send(player, new AboardNetwork.Sync(player.getId(), vehicle.uniqueUUID, (float) local.x, (float) local.y, (float) local.z));
    }

    /** To everyone else who can see this player (their own client simulates itself). */
    private static void send(ServerPlayer player, AboardNetwork.Sync msg) {
        AboardNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> player), msg);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Aboard.SERVER.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        Aboard.SERVER.clear();
        PENDING_STAND_UP.clear();
    }

    private AboardServer() {
    }
}
