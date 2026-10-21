package gapcabinwalk.cabin;

import gapcabinwalk.GapCabinWalk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walkable floor plan of an aircraft cabin, in the vehicle's local (model) coordinates.
 * Generated from the plane's OBJ model by tools/gen_cabin_maps.py and stored at
 * assets/gapcabinwalk/cabins/<packID>/<modelName>.json.
 *
 * Each grid cell is either blocked (0) or walkable with a floor height of
 * yMin + (value - 1) * yStep.
 */
public final class CabinMap {
    /** Half-width of the walking player's footprint, in blocks. Smaller than vanilla's 0.3 so aisles fit. */
    public static final double PLAYER_RADIUS = 0.1;

    private static final Map<String, Optional<CabinMap>> CACHE = new ConcurrentHashMap<>();

    public final double cell, x0, z0, yMin, yStep;
    public final int nx, nz;
    /** Window/portal geometry, measured from the model: cabin floor height, fuselage outer half-width
     *  at window height, walkable cabin z-extent and the nose (front of the windshield). */
    public final double floorLevel, halfWidth, cabinZ0, cabinZ1, noseZ;
    private final byte[] cells;

    private CabinMap(JsonObject json) {
        cell = json.get("cell").getAsDouble();
        x0 = json.get("x0").getAsDouble();
        z0 = json.get("z0").getAsDouble();
        nx = json.get("nx").getAsInt();
        nz = json.get("nz").getAsInt();
        yMin = json.get("yMin").getAsDouble();
        yStep = json.get("yStep").getAsDouble();
        cells = Base64.getDecoder().decode(json.get("cells").getAsString());
        floorLevel = json.has("floorLevel") ? json.get("floorLevel").getAsDouble() : yMin;
        halfWidth = json.has("halfWidth") ? json.get("halfWidth").getAsDouble() : -x0;
        cabinZ0 = json.has("cabinZ0") ? json.get("cabinZ0").getAsDouble() : z0;
        cabinZ1 = json.has("cabinZ1") ? json.get("cabinZ1").getAsDouble() : z0 + nz * cell;
        noseZ = json.has("noseZ") ? json.get("noseZ").getAsDouble() : cabinZ1 + 2;
        if (cells.length != nx * nz) {
            throw new IllegalArgumentException("cabin map has " + cells.length + " cells, expected " + nx * nz);
        }
    }

    /** Model name MTS renders this vehicle with; cabin maps are keyed by it. */
    public static String modelNameOf(EntityVehicleF_Physics vehicle) {
        if (vehicle.subDefinition != null && vehicle.subDefinition.modelName != null) {
            return vehicle.subDefinition.modelName;
        }
        return vehicle.definition.systemName;
    }

    /** The cabin map for this vehicle, or null if it has none. */
    @Nullable
    public static CabinMap forVehicle(@Nullable EntityVehicleF_Physics vehicle) {
        if (vehicle == null) {
            return null;
        }
        String packID = vehicle.definition.packID;
        String model = modelNameOf(vehicle);
        return CACHE.computeIfAbsent(packID + ":" + model, k -> Optional.ofNullable(load(packID, model))).orElse(null);
    }

    @Nullable
    private static CabinMap load(String packID, String model) {
        String path = "/assets/" + GapCabinWalk.MODID + "/cabins/" + packID + "/" + model + ".json";
        try (InputStream in = CabinMap.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new CabinMap(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger().error("Failed to load cabin map {}", path, e);
            return null;
        }
    }

    private int raw(double x, double z) {
        int ix = (int) Math.floor((x - x0) / cell);
        int iz = (int) Math.floor((z - z0) / cell);
        if (ix < 0 || iz < 0 || ix >= nx || iz >= nz) {
            return 0;
        }
        return cells[iz * nx + ix] & 0xFF;
    }

    public boolean isWalkable(double x, double z) {
        return raw(x, z) != 0;
    }

    /** Can a player whose footprint is radius r stand centred at (x, z)? */
    public boolean canStand(double x, double z, double r) {
        return isWalkable(x, z) && isWalkable(x - r, z - r) && isWalkable(x + r, z - r)
                && isWalkable(x - r, z + r) && isWalkable(x + r, z + r);
    }

    /** Floor height under (x, z); falls back to nearby footprint corners, NaN if none is walkable. */
    public double floorAt(double x, double z) {
        int v = raw(x, z);
        if (v == 0) {
            double r = PLAYER_RADIUS;
            v = Math.max(Math.max(raw(x - r, z - r), raw(x + r, z - r)), Math.max(raw(x - r, z + r), raw(x + r, z + r)));
        }
        return v == 0 ? Double.NaN : yMin + (v - 1) * yStep;
    }

    /** Closest standable spot (cell centre) to (x, z) within maxDist, as {x, z}; null if none. */
    @Nullable
    public double[] nearestStandable(double x, double z, double maxDist) {
        double best = maxDist * maxDist;
        double[] result = null;
        for (int iz = 0; iz < nz; iz++) {
            double cz = z0 + (iz + 0.5) * cell;
            double dz = cz - z;
            if (dz * dz > best) {
                continue;
            }
            for (int ix = 0; ix < nx; ix++) {
                if (cells[iz * nx + ix] == 0) {
                    continue;
                }
                double cx = x0 + (ix + 0.5) * cell;
                double d = (cx - x) * (cx - x) + dz * dz;
                if (d < best && canStand(cx, cz, PLAYER_RADIUS)) {
                    best = d;
                    result = new double[]{cx, cz};
                }
            }
        }
        return result;
    }
}
