package gapcabinwalk.aboard;

import gapcabinwalk.cabin.CabinMap;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Collision in the aircraft's local frame. Called from the Entity.collide mixin. */
public final class AboardPhysics {

    /**
     * Returns the collided movement for the local player while aboard an aircraft, or null to let
     * vanilla (and MTS's own world-space box collision) handle it.
     */
    @Nullable
    public static Vec3 collide(Entity entity, Vec3 movement) {
        if (!(entity instanceof Player player) || !player.level().isClientSide || !AboardClientHooks.isLocalPlayer(player)) {
            return null;
        }
        Aboard.State state = Aboard.CLIENT.get(player.getUUID());
        if (state == null) {
            return null;
        }
        EntityVehicleF_Physics vehicle = Aboard.find(player.level(), state.vehicleId);
        if (vehicle == null) {
            return null;
        }

        // Player box and movement in the aircraft's frame.
        AABB worldBox = player.getBoundingBox();
        Vec3 center = worldBox.getCenter();
        Point3D c = Aboard.toLocal(vehicle, center.x, center.y, center.z);
        double hx = worldBox.getXsize() / 2, hy = worldBox.getYsize() / 2, hz = worldBox.getZsize() / 2;
        AABB box = new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
        Point3D m = vehicle.orientation.reOrigin(new Point3D(movement.x, movement.y, movement.z));
        Vec3 move = new Vec3(m.x, m.y, m.z);

        CabinMap map = CabinMap.forVehicle(vehicle);
        List<AABB> virtual = map != null ? Aboard.virtualShell(map, Aboard.openDoors(vehicle, map)) : List.of();
        AABB near = box.expandTowards(move).inflate(1.0);
        List<AABB> real = Aboard.localBoxes(vehicle, near);

        // Safety net: if our feet ended up slightly inside a floor box (small jitter, a step, the floor
        // moving up under us), vanilla collision would ignore that box and we'd sink through it.
        // Lift back on top instead, and skip gravity for this tick.
        double lift = 0;
        double feet = box.minY;
        AABB footprint = box.deflate(0.05, 0, 0.05);
        for (AABB b : real) {
            lift = Math.max(lift, liftOut(b, footprint, feet));
        }
        if (!virtual.isEmpty()) {
            lift = Math.max(lift, liftOut(virtual.get(0), footprint, feet)); // virtual cabin floor
        }
        if (lift > 0) {
            box = box.move(0, lift, 0);
            move = new Vec3(move.x, Math.max(move.y, 0), move.z);
        }

        List<VoxelShape> shapes = new ArrayList<>();
        for (AABB local : real) {
            shapes.add(Shapes.create(local));
        }
        for (AABB local : virtual) {
            // Never trap someone who is already outside the virtual wall (e.g. boarding on the ground).
            if (local.intersects(near) && !local.intersects(box)) {
                shapes.add(Shapes.create(local));
            }
        }
        Vec3 result = collideWithShapes(move, box, shapes);

        // Step up (same rules as vanilla Entity.collide) so small ledges in the cabin can be walked over.
        float step = player.maxUpStep();
        boolean blockedHorizontally = result.x != move.x || result.z != move.z;
        boolean landed = move.y < 0 && result.y != move.y;
        if (step > 0 && blockedHorizontally && (player.onGround() || landed)) {
            Vec3 stepped = collideWithShapes(new Vec3(move.x, step, move.z), box, shapes);
            Vec3 upOnly = collideWithShapes(new Vec3(0, step, 0), box.expandTowards(move.x, 0, move.z), shapes);
            if (upOnly.y < step) {
                Vec3 across = collideWithShapes(new Vec3(move.x, 0, move.z), box.move(upOnly), shapes).add(upOnly);
                if (across.horizontalDistanceSqr() > stepped.horizontalDistanceSqr()) {
                    stepped = across;
                }
            }
            if (stepped.horizontalDistanceSqr() > result.horizontalDistanceSqr()) {
                result = stepped.add(collideWithShapes(new Vec3(0, -stepped.y + move.y, 0), box.move(stepped), shapes));
            }
        }

        result = result.add(0, lift, 0);

        // Back to world space. Only what the collision CHANGED is rotated back, and if nothing blocked us
        // sideways in the aircraft's frame, our horizontal movement is passed through untouched. Otherwise
        // the floor of a slightly tilted aircraft (nose-up on its gear) would shave a sliver off every
        // step, which vanilla treats as walking into a wall - and that cancels sprinting.
        boolean hitSideways = result.x != move.x || result.z != move.z;
        Point3D change = vehicle.orientation.rotate(new Point3D(result.x - move.x, result.y - move.y, result.z - move.z));
        Vec3 world = new Vec3(
                hitSideways ? movement.x + change.x : movement.x,
                movement.y + change.y,
                hitSideways ? movement.z + change.z : movement.z);
        if (lift > 0) {
            // The lift changed "move" itself (gravity dropped); express everything from the original movement.
            Point3D full = vehicle.orientation.rotate(new Point3D(result.x, result.y, result.z));
            world = new Vec3(hitSideways ? full.x : movement.x, full.y, hitSideways ? full.z : movement.z);
        }
        return Entity.collideBoundingBox(player, world, worldBox, player.level(), List.of());
    }

    /** How far to raise feet that are slightly (< 0.3) inside the top of this box; 0 if not. */
    private static double liftOut(AABB b, AABB footprint, double feet) {
        boolean over = b.maxX > footprint.minX && b.minX < footprint.maxX && b.maxZ > footprint.minZ && b.minZ < footprint.maxZ;
        double depth = b.maxY - feet;
        return over && b.minY < feet && depth > 1.0E-7 && depth <= 0.3 ? depth : 0;
    }

    /** Vanilla's per-axis box collision (Y first, then the larger horizontal axis last). */
    private static Vec3 collideWithShapes(Vec3 move, AABB box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return move;
        }
        double x = move.x, y = move.y, z = move.z;
        if (y != 0) {
            y = Shapes.collide(Direction.Axis.Y, box, shapes, y);
            if (y != 0) {
                box = box.move(0, y, 0);
            }
        }
        boolean zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
            if (z != 0) {
                box = box.move(0, 0, z);
            }
        }
        if (x != 0) {
            x = Shapes.collide(Direction.Axis.X, box, shapes, x);
            if (!zFirst && x != 0) {
                box = box.move(x, 0, 0);
            }
        }
        if (!zFirst && z != 0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        }
        return new Vec3(x, y, z);
    }

    private AboardPhysics() {
    }
}
