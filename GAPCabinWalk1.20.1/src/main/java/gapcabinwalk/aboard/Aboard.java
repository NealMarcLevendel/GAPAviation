package gapcabinwalk.aboard;

import gapcabinwalk.cabin.CabinMap;
import mcinterface1201.WrapperWorld;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.components.AEntityA_Base;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Ship-space" walking on Immersive Vehicles aircraft, the way Create: Aeronautics / Valkyrien Skies
 * handle moving ships:
 *
 *  - A player is ABOARD an aircraft while they are inside its cabin (or standing on its hull).
 *    They stay a completely normal, non-riding player in the real world.
 *  - CARRY: at the start of every player tick the player is put back at the same spot in the
 *    aircraft's frame (position + yaw), wherever the aircraft has moved/turned since last tick.
 *  - COLLISION: the player's movement is converted into the aircraft's local frame and collided
 *    against the aircraft's collision boxes there (where they really are axis-aligned boxes),
 *    then converted back. So the hull is solid at any heading, pitch or bank.
 *  - Then vanilla does everything else: walking, sprinting, jumping, crouching, leg animation, items.
 *
 * The local player is simulated on its own client (client-authoritative movement, like vanilla).
 * The server tracks who is aboard and tells other clients each aboard player's aircraft-relative
 * position so they see them moving smoothly with the aircraft.
 */
public final class Aboard {
    /** Aircraft-relative state of one aboard player. */
    public static final class State {
        public UUID vehicleId;
        public double lx, ly, lz;
        /** MTS yaw of the aircraft when the local position was recorded. */
        public double vehicleYaw;
        /** Leg-animation state driven by movement relative to the aircraft. */
        public boolean animInit;
        public double animLx, animLz;
        public float animSpeed, animPos;
        public int age;
    }

    /** Keyed by player UUID. One map per logical side. */
    public static final Map<UUID, State> CLIENT = new HashMap<>();
    public static final Map<UUID, State> SERVER = new HashMap<>();

    public static Map<UUID, State> side(boolean client) {
        return client ? CLIENT : SERVER;
    }

    public static boolean isAboard(boolean client, UUID player) {
        return side(client).containsKey(player);
    }

    @Nullable
    public static EntityVehicleF_Physics find(Level level, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        AEntityA_Base entity = WrapperWorld.getWrapperFor(level).getEntity(id);
        return entity instanceof EntityVehicleF_Physics vehicle && vehicle.isValid ? vehicle : null;
    }

    public static Point3D toLocal(EntityVehicleF_Physics v, double x, double y, double z) {
        return v.orientation.reOrigin(new Point3D(x, y, z).subtract(v.position));
    }

    public static Point3D toWorld(EntityVehicleF_Physics v, double x, double y, double z) {
        return v.orientation.rotate(new Point3D(x, y, z)).add(v.position);
    }

    /** MTS yaw of an aircraft (computed on a copy so the live matrix isn't touched). */
    public static double yaw(EntityVehicleF_Physics v) {
        return new RotationMatrix().set(v.orientation).convertToAngles().y;
    }

    /** The aircraft (with a cabin map) whose cabin or hull contains this point, if any. */
    @Nullable
    public static EntityVehicleF_Physics findContaining(Level level, double x, double y, double z) {
        for (EntityVehicleF_Physics v : new ArrayList<>(WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class))) {
            if (v.isValid && v.position.distanceTo(new Point3D(x, y, z)) < 80 && contains(v, x, y, z)) {
                return v;
            }
        }
        return null;
    }

    /** Is this world point inside the aircraft's cabin volume, or standing on top of one of its boxes? */
    public static boolean contains(EntityVehicleF_Physics v, double x, double y, double z) {
        CabinMap map = CabinMap.forVehicle(v);
        if (map == null) {
            return false;
        }
        Point3D l = toLocal(v, x, y, z);
        if (Math.abs(l.x) < map.halfWidth + 0.2 && l.y > map.floorLevel - 0.6 && l.y < map.floorLevel + 2.8
                && l.z > map.cabinZ0 - 3 && l.z < map.noseZ) {
            return true;
        }
        for (BoundingBox box : entityBoxes(v)) {
            Point3D c = localCenterOf(v, box);
            double top = c.y + box.heightRadius;
            if (Math.abs(l.x - c.x) < box.widthRadius + 0.3 && Math.abs(l.z - c.z) < box.depthRadius + 0.3
                    && l.y >= top - 0.2 && l.y <= top + 0.8) {
                return true;
            }
        }
        return false;
    }

    /** The aircraft's walkable/solid boxes ("entity" collision type), from MTS. */
    public static List<BoundingBox> entityBoxes(EntityVehicleF_Physics v) {
        List<BoundingBox> list = new ArrayList<>();
        for (BoundingBox box : new ArrayList<>(v.allCollisionBoxes)) {
            if (box.collisionTypes != null && box.collisionTypes.contains(JSONCollisionGroup.CollisionType.ENTITY)) {
                list.add(box);
            }
        }
        return list;
    }

    /** The aircraft's entity boxes in its LOCAL frame, where they are true axis-aligned boxes. */
    public static List<AABB> localBoxes(EntityVehicleF_Physics v, AABB near) {
        List<AABB> list = new ArrayList<>();
        for (BoundingBox box : entityBoxes(v)) {
            Point3D c = localCenterOf(v, box);
            AABB local = new AABB(c.x - box.widthRadius, c.y - box.heightRadius, c.z - box.depthRadius,
                    c.x + box.widthRadius, c.y + box.heightRadius, c.z + box.depthRadius);
            if (local.intersects(near)) {
                list.add(local);
            }
        }
        return list;
    }

    /**
     * Exact centre of a box in the aircraft's frame. MTS rounds globalCenter to a 1/64 grid every tick,
     * so converting that back would make the floor jitter up and down by a 64th of a block - enough
     * for vanilla collision to stop seeing it under your feet. For the aircraft's own non-animated
     * boxes use the exact definition position; only animated or part boxes go through globalCenter.
     */
    private static Point3D localCenterOf(EntityVehicleF_Physics v, BoundingBox box) {
        if (!isAnimated(box) && v.collisionBoxes.contains(box)) {
            return box.localCenter.copy().multiply(v.scale);
        }
        return toLocal(v, box.globalCenter.x, box.globalCenter.y, box.globalCenter.z);
    }

    /** Boxes that move: own animations, or animations borrowed from a model object (applyAfter, e.g. doors). */
    private static boolean isAnimated(BoundingBox box) {
        return box.groupDef == null
                || (box.groupDef.animations != null && !box.groupDef.animations.isEmpty())
                || box.groupDef.applyAfter != null;
    }

    /** A box that belongs to a door (its handle/click box swings with the door). */
    private static boolean isDoorBox(BoundingBox box) {
        if (box.groupDef != null && box.groupDef.applyAfter != null && box.groupDef.applyAfter.toLowerCase().contains("door")) {
            return true;
        }
        return box.definition != null && box.definition.variableName != null && box.definition.variableName.toLowerCase().contains("door");
    }

    /**
     * Openings in the side walls where a cabin door is currently open, as {side (+1/-1), zMin, zMax}.
     * A door counts as open when its box has moved away from its closed (definition) position.
     */
    public static List<double[]> openDoors(EntityVehicleF_Physics v, CabinMap map) {
        List<double[]> doors = new ArrayList<>();
        for (BoundingBox box : entityBoxes(v)) {
            if (!v.collisionBoxes.contains(box) || !isDoorBox(box)) {
                continue;
            }
            Point3D closed = box.localCenter.copy().multiply(v.scale);
            if (closed.y < map.floorLevel - 0.5 || closed.y > map.floorLevel + 2.5 || Math.abs(closed.x) < map.halfWidth * 0.5) {
                continue; // cargo doors under the floor, lavatory doors inside the cabin, etc.
            }
            Point3D live = toLocal(v, box.globalCenter.x, box.globalCenter.y, box.globalCenter.z);
            if (live.distanceTo(closed) > 0.15) {
                doors.add(new double[]{Math.signum(closed.x), closed.z - 0.8, closed.z + 0.8});
            }
        }
        return doors;
    }

    /**
     * Extra invisible collision in the aircraft's local frame, measured from the model, for packs whose
     * collision shell has gaps: a cabin floor, side walls and front/rear caps. The side walls have gaps
     * wherever a door is open, so you leave through real open doors (on the ground or, if you insist,
     * in flight).
     */
    public static List<AABB> virtualShell(CabinMap map, List<double[]> openDoors) {
        List<AABB> list = new ArrayList<>();
        double f = map.floorLevel;
        double z0 = map.cabinZ0 - 0.5, z1 = map.cabinZ1 + 0.5;
        list.add(new AABB(-map.halfWidth, f - 1.0, z0, map.halfWidth, f, z1)); // floor: always first
        double w = map.halfWidth - 0.1;
        for (int side : new int[]{1, -1}) {
            // Wall segments between open doors on this side.
            List<double[]> gaps = new ArrayList<>();
            for (double[] d : openDoors) {
                if (d[0] == side) {
                    gaps.add(new double[]{d[1], d[2]});
                }
            }
            gaps.sort((a, b) -> Double.compare(a[0], b[0]));
            double start = z0 - 1;
            for (double[] gap : gaps) {
                if (gap[0] > start) {
                    list.add(sideWall(side, w, f, start, gap[0]));
                }
                start = Math.max(start, gap[1]);
            }
            if (start < z1 + 1) {
                list.add(sideWall(side, w, f, start, z1 + 1));
            }
        }
        list.add(new AABB(-w, f - 1.0, z0 - 1.0, w, f + 3.0, z0));
        list.add(new AABB(-w, f - 1.0, z1, w, f + 3.0, z1 + 1.0));
        return list;
    }

    private static AABB sideWall(int side, double w, double f, double za, double zb) {
        return side > 0 ? new AABB(w, f - 1.0, za, w + 1.0, f + 3.0, zb) : new AABB(-w - 1.0, f - 1.0, za, -w, f + 3.0, zb);
    }

    /** Top of the solid floor under a local (x, z) near height y: highest box top within 0.5 of y. */
    public static double floorTop(EntityVehicleF_Physics v, CabinMap map, double x, double z, double y) {
        double best = Double.NEGATIVE_INFINITY;
        AABB column = new AABB(x - 0.3, y - 0.5, z - 0.3, x + 0.3, y + 0.5, z + 0.3);
        List<AABB> boxes = localBoxes(v, column);
        boxes.add(virtualShell(map, List.of()).get(0)); // virtual floor only
        for (AABB box : boxes) {
            if (box.maxX > x - 0.3 && box.minX < x + 0.3 && box.maxZ > z - 0.3 && box.minZ < z + 0.3
                    && box.maxY > y - 0.5 && box.maxY < y + 0.5) {
                best = Math.max(best, box.maxY);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? y : best;
    }

    /**
     * Nearest place (local x, floor y, z) to (x, z) where a full-size standing player fits without
     * touching any of the aircraft's boxes. Null if there's none within maxDist.
     */
    @Nullable
    public static double[] findStandSpot(EntityVehicleF_Physics v, CabinMap map, double x, double z, double maxDist,
                                         double halfWidth, double height) {
        List<double[]> candidates = new ArrayList<>();
        for (double cz = z - maxDist; cz <= z + maxDist; cz += map.cell) {
            for (double cx = -map.halfWidth; cx <= map.halfWidth; cx += map.cell) {
                double d = (cx - x) * (cx - x) + (cz - z) * (cz - z);
                if (d <= maxDist * maxDist && map.canStand(cx, cz, CabinMap.PLAYER_RADIUS)) {
                    candidates.add(new double[]{cx, cz, d});
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a[2], b[2]));
        for (double[] c : candidates) {
            double floor = floorTop(v, map, c[0], c[1], map.floorAt(c[0], c[1]));
            AABB player = new AABB(c[0] - halfWidth, floor + 0.001, c[1] - halfWidth, c[0] + halfWidth, floor + 0.001 + height, c[1] + halfWidth);
            boolean blocked = false;
            for (AABB box : localBoxes(v, player)) {
                if (box.deflate(1.0E-4).intersects(player)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return new double[]{c[0], floor, c[1]};
            }
        }
        return null;
    }

    private Aboard() {
    }
}
