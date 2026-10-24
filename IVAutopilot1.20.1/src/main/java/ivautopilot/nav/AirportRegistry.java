package ivautopilot.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All Airport Markers in the world (saved with it). Two markers with the same name are the opposite
 * corners of that airport's rectangle. The airports are worked out once whenever a marker changes, so
 * the per-tick lookups are just a few rectangle checks.
 */
public class AirportRegistry extends SavedData {
    private static final String ID = "ivautopilot_airports";
    /** dimension -> marker position -> airport name. */
    private final Map<String, Map<BlockPos, String>> markers = new HashMap<>();
    /** dimension -> complete airports (exactly two corners). */
    private final Map<String, List<Airport>> airports = new HashMap<>();

    public static AirportRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(AirportRegistry::load, AirportRegistry::new, ID);
    }

    public void put(ResourceKey<Level> dim, BlockPos pos, String name) {
        markers.computeIfAbsent(dim.location().toString(), k -> new LinkedHashMap<>()).put(pos.immutable(), name);
        rebuild(dim.location().toString());
        setDirty();
    }

    public void remove(ResourceKey<Level> dim, BlockPos pos) {
        Map<BlockPos, String> byPos = markers.get(dim.location().toString());
        if (byPos != null && byPos.remove(pos) != null) {
            rebuild(dim.location().toString());
            setDirty();
        }
    }

    private void rebuild(String dim) {
        Map<String, List<BlockPos>> byName = new LinkedHashMap<>();
        markers.getOrDefault(dim, Map.of()).forEach((pos, name) -> byName.computeIfAbsent(key(name), k -> new ArrayList<>()).add(pos));
        List<Airport> list = new ArrayList<>();
        Map<BlockPos, String> byPos = markers.getOrDefault(dim, Map.of());
        byName.forEach((k, corners) -> {
            if (corners.size() == 2) {
                list.add(new Airport(byPos.get(corners.get(0)), corners.get(0), corners.get(1)));
            }
        });
        airports.put(dim, list);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public List<Airport> all(ResourceKey<Level> dim) {
        return airports.getOrDefault(dim.location().toString(), List.of());
    }

    /** Markers with this name (0, 1 = waiting for its other corner, 2 = complete, more = too many). */
    public List<BlockPos> corners(ResourceKey<Level> dim, String name) {
        List<BlockPos> out = new ArrayList<>();
        markers.getOrDefault(dim.location().toString(), Map.of()).forEach((pos, n) -> {
            if (key(n).equals(key(name))) {
                out.add(pos);
            }
        });
        return out;
    }

    /** Name for a new unnamed marker: the nearest airport still missing its second corner, else "Airport N". */
    public String nameForNewMarker(ResourceKey<Level> dim, BlockPos pos) {
        Map<BlockPos, String> byPos = markers.getOrDefault(dim.location().toString(), Map.of());
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, String> e : byPos.entrySet()) {
            if (!e.getKey().equals(pos) && corners(dim, e.getValue()).size() == 1 && e.getKey().distSqr(pos) < bestD) {
                bestD = e.getKey().distSqr(pos);
                best = e.getValue();
            }
        }
        if (best != null) {
            return best;
        }
        for (int i = 1; ; i++) {
            String name = "Airport " + i;
            if (corners(dim, name).isEmpty()) {
                return name;
            }
        }
    }

    /** The airport whose area contains this point, if any. */
    @Nullable
    public Airport containing(ResourceKey<Level> dim, double x, double y, double z, double ceiling) {
        for (Airport airport : all(dim)) {
            if (airport.contains(x, y, z, ceiling)) {
                return airport;
            }
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        markers.forEach((dim, byPos) -> byPos.forEach((pos, name) -> {
            CompoundTag t = new CompoundTag();
            t.putString("dim", dim);
            t.putString("name", name);
            t.put("pos", NbtUtils.writeBlockPos(pos));
            list.add(t);
        }));
        tag.put("airports", list);
        return tag;
    }

    private static AirportRegistry load(CompoundTag tag) {
        AirportRegistry registry = new AirportRegistry();
        for (Tag t : tag.getList("airports", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            BlockPos pos = NbtUtils.readBlockPos(c.getCompound("pos"));
            String name = c.getString("name");
            registry.markers.computeIfAbsent(c.getString("dim"), k -> new LinkedHashMap<>()).put(pos, name.isEmpty() ? "Airport" : name);
        }
        registry.markers.keySet().forEach(registry::rebuild);
        return registry;
    }
}
