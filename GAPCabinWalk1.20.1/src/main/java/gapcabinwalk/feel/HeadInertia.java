package gapcabinwalk.feel;

import com.mojang.blaze3d.platform.InputConstants;
import gapcabinwalk.GapCabinWalk;
import gapcabinwalk.aboard.Aboard;
import mcinterface1201.BuilderEntityLinkedSeat;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartSeat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Head inertia. Your head is modelled as a damped spring on your neck, sitting in the vehicle:
 *  - when the vehicle starts rotating (a quick roll into a bank, a pitch-up, a sudden yaw) the head
 *    can't follow instantly: it lags behind, then catches up with a little overshoot, and the same
 *    when the rotation stops;
 *  - speeding up pushes it back (you look slightly up), braking throws it forward (slightly down),
 *    bumps and sideways pushes make it nod and sway.
 * The offset is worked out in the vehicle's frame and turned into camera yaw/pitch/roll for
 * whichever way you're looking, then added on top of Immersive Vehicles' own camera.
 *
 * First person only, seated or walking the cabin, in any Immersive Vehicles vehicle.
 */
@Mod.EventBusSubscriber(modid = GapCabinWalk.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeadInertia {
    public static final KeyMapping TOGGLE = new KeyMapping("key.gapcabinwalk.head_inertia", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.gapcabinwalk");

    private static final int SUBSTEPS = 4;
    private static final double DT = 0.05;

    @Nullable
    private static UUID vehicleId;
    @Nullable
    private static RotationMatrix lastOrientation;
    private static Point3D lastVelocity, lastAngularVelocity;
    /** Head offset (rotation vector, radians, vehicle frame: x = left, y = up, z = forward) and its rate. */
    private static Point3D offset = new Point3D(), rate = new Point3D(), previousOffset = new Point3D();
    private static int warmup;

    private HeadInertia() {
    }

    @Mod.EventBusSubscriber(modid = GapCabinWalk.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE);
        }
    }

    @Nullable
    private static EntityVehicleF_Physics vehicleOf(Player player) {
        if (player.getVehicle() instanceof BuilderEntityLinkedSeat seatBuilder && seatBuilder.entity instanceof PartSeat seat
                && seat.vehicleOn != null) {
            return seat.vehicleOn;
        }
        Aboard.State aboard = Aboard.CLIENT.get(player.getUUID());
        return aboard == null ? null : Aboard.find(player.level(), aboard.vehicleId);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        while (TOGGLE.consumeClick()) {
            boolean on = !HeadInertiaConfig.ENABLED.get();
            HeadInertiaConfig.ENABLED.set(on);
            HeadInertiaConfig.SPEC.save();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(on ? "msg.gapcabinwalk.head_inertia_on" : "msg.gapcabinwalk.head_inertia_off"), true);
            }
        }
        previousOffset = offset.copy();
        EntityVehicleF_Physics vehicle = mc.player == null || mc.isPaused() ? null : vehicleOf(mc.player);
        if (vehicle == null || !HeadInertiaConfig.ENABLED.get()) {
            reset(null);
            return;
        }
        if (!vehicle.uniqueUUID.equals(vehicleId)) {
            reset(vehicle.uniqueUUID);
        }
        RotationMatrix orientation = new RotationMatrix().set(vehicle.orientation);
        Point3D velocity = new Point3D(vehicle.position.x - vehicle.prevPosition.x, vehicle.position.y - vehicle.prevPosition.y,
                vehicle.position.z - vehicle.prevPosition.z).scale(20); // blocks/s, world
        if (lastOrientation == null) {
            lastOrientation = orientation;
            lastVelocity = velocity;
            lastAngularVelocity = new Point3D();
            return;
        }
        // Angular velocity in the vehicle frame (rad/s), from how its forward and up axes moved since last tick.
        Point3D forward = lastOrientation.reOrigin(orientation.rotate(new Point3D(0, 0, 1)));
        Point3D up = lastOrientation.reOrigin(orientation.rotate(new Point3D(0, 1, 0)));
        Point3D angular = new Point3D(-forward.y, forward.x, -up.x).scale(20); // pitch (about left), yaw (about up), roll (about forward)
        Point3D angularAccel = angular.copy().subtract(lastAngularVelocity).scale(20);
        angularAccel.z *= HeadInertiaConfig.ROLL_STRENGTH.get(); // roll lag gets its own extra gain
        // What the body feels (specific force = acceleration minus gravity), in the vehicle frame, less the
        // normal 1 g: so a coordinated banked turn presses you into the seat instead of tipping your head
        // sideways, while braking, speeding up, bumps and skids push the head around. Gravity in world
        // blocks/s^2 is scaled by MTS's speed factor like all its motion.
        double g = 0.0245 * 400 * (vehicle.speedFactor > 0 ? vehicle.speedFactor : 1);
        Point3D felt = velocity.copy().subtract(lastVelocity).scale(20).add(0, g, 0);
        Point3D accel = orientation.reOrigin(felt).subtract(new Point3D(0, g, 0));
        lastOrientation = orientation;
        lastVelocity = velocity;
        lastAngularVelocity = angular;
        if (warmup > 0) {
            warmup--; // ignore the jolt of getting in / standing up / teleports
            return;
        }
        clamp(angularAccel, 60);
        clamp(accel, 60);

        double strength = HeadInertiaConfig.STRENGTH.get();
        double accelStrength = HeadInertiaConfig.ACCELERATION_STRENGTH.get() * 0.007; // rad per block/s^2
        double w = 2 * Math.PI * HeadInertiaConfig.FREQUENCY.get();
        double k = w * w, c = 2 * HeadInertiaConfig.DAMPING.get() * w;
        // Where acceleration pushes the head: forward accel -> looks up (negative about left), braking -> down;
        // upward accel -> nods down; pushed toward the left -> tilts right (about forward).
        Point3D rest = new Point3D(-accel.z * accelStrength + accel.y * accelStrength * 0.5, 0, accel.x * accelStrength);
        double h = DT / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            // offset'' = -k (offset - rest) - c offset' - strength * vehicle angular acceleration
            Point3D acc = offset.copy().subtract(rest).scale(-k).add(rate.copy().scale(-c)).add(angularAccel.copy().scale(-strength));
            rate.add(acc.scale(h));
            offset.add(rate.copy().scale(h));
        }
        double max = Math.toRadians(HeadInertiaConfig.MAX_ANGLE.get());
        clamp(offset, max);
    }

    private static void clamp(Point3D p, double max) {
        p.x = Math.max(-max, Math.min(max, p.x));
        p.y = Math.max(-max, Math.min(max, p.y));
        p.z = Math.max(-max, Math.min(max, p.z));
    }

    private static void reset(@Nullable UUID vehicle) {
        vehicleId = vehicle;
        lastOrientation = null;
        offset = new Point3D();
        rate = new Point3D();
        previousOffset = new Point3D();
        warmup = 10;
    }

    /** After Immersive Vehicles has set the camera: add the head offset, in the camera's own axes. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (vehicleId == null || mc.player == null || !mc.options.getCameraType().isFirstPerson() || !HeadInertiaConfig.ENABLED.get()) {
            return;
        }
        EntityVehicleF_Physics vehicle = vehicleOf(mc.player);
        if (vehicle == null) {
            return;
        }
        boolean standing = !mc.player.isPassenger();
        double t = event.getPartialTick();
        Point3D local = new Point3D(previousOffset.x + (offset.x - previousOffset.x) * t,
                previousOffset.y + (offset.y - previousOffset.y) * t, previousOffset.z + (offset.z - previousOffset.z) * t);
        Point3D head = vehicle.orientation.rotate(local.copy()); // head-inertia offset, world frame
        Point3D tilt = new Point3D();
        if (standing && HeadInertiaConfig.TILT_WHEN_STANDING.get()) {
            // Walking the cabin: Minecraft keeps a standing player's view level, so you don't see the cabin
            // bank around you. Tilt world-up onto the aircraft's up - roll part only, so aiming stays true:
            // looking forward you bank with it, looking out a side window you see its pitch.
            Point3D up = vehicle.orientation.rotate(new Point3D(0, 1, 0));
            Point3D axis = new Point3D(up.z, 0, -up.x); // (0,1,0) x up
            double sin = Math.sqrt(axis.x * axis.x + axis.z * axis.z);
            if (sin > 1.0E-6) {
                tilt = axis.scale(Math.atan2(sin, up.y) / sin);
            }
        }
        // Camera axes (Minecraft: yaw 0 = south, pitch > 0 = looking down, roll > 0 = head tilted right).
        double yaw = Math.toRadians(event.getYaw()), pitch = Math.toRadians(event.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = 0 * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - 0 * fx; // up = right x forward
        double aboutForward = (head.x + tilt.x) * fx + (head.y + tilt.y) * fy + (head.z + tilt.z) * fz;
        double aboutRight = head.x * rx + head.z * rz;
        double aboutUp = head.x * ux + head.y * uy + head.z * uz;
        event.setRoll(event.getRoll() + (float) Math.toDegrees(aboutForward));
        event.setPitch(event.getPitch() - (float) Math.toDegrees(aboutRight));
        event.setYaw(event.getYaw() - (float) Math.toDegrees(aboutUp));
    }
}
