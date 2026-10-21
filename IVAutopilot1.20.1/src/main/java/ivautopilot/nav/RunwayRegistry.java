package ivautopilot.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** All runway markers in the world, saved with the world. Two markers with the same name = one runway. */
public class RunwayRegistry extends SavedData {
    private static final String ID = "ivautopilot_runways";
    /** dimension -> runway name (case-insensitive) -> marker positions. */
    private final Map<String, Map<String, List<BlockPos>>> markers = new TreeMap<>();
    private final Map<String, String> displayNames = new TreeMap<>();

    public static RunwayRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(RunwayRegistry::load, RunwayRegistry::new, ID);
    }

    private static String key(String name) {
        return name.trim().toLowerCase();
    }

    public void addMarker(ResourceKey<Level> dim, String name, BlockPos pos) {
        List<BlockPos> list = markers.computeIfAbsent(dim.location().toString(), k -> new TreeMap<>())
                .computeIfAbsent(key(name), k -> new ArrayList<>());
        if (!list.contains(pos)) {
            list.add(pos.immutable());
        }
        displayNames.put(key(name), name.trim());
        setDirty();
    }

    public void removeMarker(ResourceKey<Level> dim, String name, BlockPos pos) {
        Map<String, List<BlockPos>> byName = markers.get(dim.location().toString());
        if (byName != null && byName.containsKey(key(name))) {
            byName.get(key(name)).remove(pos);
            if (byName.get(key(name)).isEmpty()) {
                byName.remove(key(name));
            }
            setDirty();
        }
    }

    /** The runway with this name, if exactly two markers with that name exist in this dimension. */
    @Nullable
    public Runway find(ResourceKey<Level> dim, String name) {
        Map<String, List<BlockPos>> byName = markers.get(dim.location().toString());
        List<BlockPos> list = byName == null ? null : byName.get(key(name));
        if (list == null || list.size() != 2) {
            return null;
        }
        BlockPos p = list.get(0), q = list.get(1);
        return new Runway(displayNames.getOrDefault(key(name), name),
                new Vec3(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5), new Vec3(q.getX() + 0.5, q.getY() + 1.0, q.getZ() + 0.5));
    }

    /** Marker positions of the runway with this name (any number). */
    public List<BlockPos> markers(ResourceKey<Level> dim, String name) {
        Map<String, List<BlockPos>> byName = markers.get(dim.location().toString());
        List<BlockPos> list = byName == null ? null : byName.get(key(name));
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /** Compass bearing (0 = north, 90 = east) from one point to another. */
    public static int bearing(BlockPos from, BlockPos to) {
        double b = Math.toDegrees(Math.atan2(to.getX() - from.getX(), -(to.getZ() - from.getZ())));
        int r = (int) Math.round(b);
        return ((r % 360) + 360) % 360;
    }

    /** Label text for one marker of a runway. */
    public String labelInfo(ResourceKey<Level> dim, String name, BlockPos pos) {
        List<BlockPos> list = markers(dim, name);
        if (list.size() == 2 && list.contains(pos)) {
            BlockPos other = list.get(0).equals(pos) ? list.get(1) : list.get(0);
            double length = Math.sqrt(Math.pow(other.getX() - pos.getX(), 2) + Math.pow(other.getZ() - pos.getZ(), 2));
            return String.format("heading %03d, %d blocks", bearing(pos, other), Math.round(length));
        }
        return list.size() + " of 2 markers";
    }

    /** Refresh the floating labels of every loaded marker of this runway. */
    public void refreshLabels(ServerLevel level, String name) {
        List<BlockPos> list = markers(level.dimension(), name);
        for (BlockPos pos : list) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof RunwayMarkerBlockEntity marker) {
                marker.setInfo(labelInfo(level.dimension(), name, pos));
            }
        }
    }

    /** Names of every runway (complete or not) in this dimension, with how many markers each has. */
    public Map<String, Integer> list(ResourceKey<Level> dim) {
        Map<String, Integer> out = new TreeMap<>();
        Map<String, List<BlockPos>> byName = markers.get(dim.location().toString());
        if (byName != null) {
            byName.forEach((k, v) -> out.put(displayNames.getOrDefault(k, k), v.size()));
        }
        return out;
    }

    /** Every complete runway in this dimension. */
    public List<Runway> all(ResourceKey<Level> dim) {
        List<Runway> out = new ArrayList<>();
        for (String name : names(dim)) {
            Runway r = find(dim, name);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public Collection<String> names(ResourceKey<Level> dim) {
        return list(dim).keySet();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag all = new ListTag();
        markers.forEach((dim, byName) -> byName.forEach((name, list) -> list.forEach(pos -> {
            CompoundTag t = new CompoundTag();
            t.putString("dim", dim);
            t.putString("name", displayNames.getOrDefault(name, name));
            t.put("pos", NbtUtils.writeBlockPos(pos));
            all.add(t);
        })));
        tag.put("markers", all);
        return tag;
    }

    private static RunwayRegistry load(CompoundTag tag) {
        RunwayRegistry registry = new RunwayRegistry();
        for (Tag t : tag.getList("markers", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(c.getString("dim")));
            registry.addMarker(dim, c.getString("name"), NbtUtils.readBlockPos(c.getCompound("pos")));
        }
        registry.setDirty(false);
        return registry;
    }
}
