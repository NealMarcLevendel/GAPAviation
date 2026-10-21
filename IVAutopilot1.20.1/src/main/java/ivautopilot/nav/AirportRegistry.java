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
import java.util.Map;

/** All airport markers (no-bank zones) in the world, saved with the world. */
public class AirportRegistry extends SavedData {
    private static final String ID = "ivautopilot_airports";
    /** dimension -> marker position -> airport. */
    private final Map<String, Map<BlockPos, Airport>> airports = new HashMap<>();

    public static AirportRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(AirportRegistry::load, AirportRegistry::new, ID);
    }

    public void put(ResourceKey<Level> dim, Airport airport) {
        airports.computeIfAbsent(dim.location().toString(), k -> new LinkedHashMap<>()).put(airport.pos().immutable(), airport);
        setDirty();
    }

    public void remove(ResourceKey<Level> dim, BlockPos pos) {
        Map<BlockPos, Airport> byPos = airports.get(dim.location().toString());
        if (byPos != null && byPos.remove(pos) != null) {
            setDirty();
        }
    }

    public List<Airport> all(ResourceKey<Level> dim) {
        Map<BlockPos, Airport> byPos = airports.get(dim.location().toString());
        return byPos == null ? List.of() : new ArrayList<>(byPos.values());
    }

    /** The airport whose no-bank zone contains this point, if any. */
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
        airports.forEach((dim, byPos) -> byPos.values().forEach(a -> {
            CompoundTag t = new CompoundTag();
            t.putString("dim", dim);
            t.putString("name", a.name());
            t.putInt("radius", a.radius());
            t.put("pos", NbtUtils.writeBlockPos(a.pos()));
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
            registry.airports.computeIfAbsent(c.getString("dim"), k -> new LinkedHashMap<>())
                    .put(pos, new Airport(c.getString("name"), pos, c.getInt("radius")));
        }
        return registry;
    }
}
