package ivautopilot.taxi;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The taxiway network, saved with the world: Taxi Waypoints (optionally named - a named waypoint is a
 * gate/stand), links between them (made with the Taxiway Wand, two-way) and runway links (waypoint
 * <-> Runway Marker: that waypoint is where planes enter the runway at that end, and leave it after
 * landing toward that end).
 */
public class TaxiNetwork extends SavedData {
    private static final String ID = "ivautopilot_taxi";

    /** One dimension's network. */
    public static final class Graph {
        public final Map<BlockPos, String> nodes = new LinkedHashMap<>();
        public final Map<BlockPos, Set<BlockPos>> links = new HashMap<>();
        /** Waypoint -> runway marker positions it connects to. */
        public final Map<BlockPos, Set<BlockPos>> runwayLinks = new HashMap<>();

        public Set<BlockPos> neighbours(BlockPos node) {
            return links.getOrDefault(node, Set.of());
        }

        @Nullable
        public BlockPos gate(String name) {
            for (Map.Entry<BlockPos, String> e : nodes.entrySet()) {
                if (!e.getValue().isEmpty() && e.getValue().equalsIgnoreCase(name.trim())) {
                    return e.getKey();
                }
            }
            return null;
        }

        /** Unique undirected links, each once. */
        public List<BlockPos[]> linkList() {
            List<BlockPos[]> out = new ArrayList<>();
            for (Map.Entry<BlockPos, Set<BlockPos>> e : links.entrySet()) {
                for (BlockPos b : e.getValue()) {
                    if (e.getKey().asLong() < b.asLong()) {
                        out.add(new BlockPos[]{e.getKey(), b});
                    }
                }
            }
            return out;
        }

        public List<BlockPos[]> runwayLinkList() {
            List<BlockPos[]> out = new ArrayList<>();
            runwayLinks.forEach((node, markers) -> markers.forEach(m -> out.add(new BlockPos[]{node, m})));
            return out;
        }
    }

    private final Map<String, Graph> graphs = new HashMap<>();

    public static TaxiNetwork get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TaxiNetwork::load, TaxiNetwork::new, ID);
    }

    public Graph graph(ResourceKey<Level> dim) {
        return graphs.computeIfAbsent(dim.location().toString(), k -> new Graph());
    }

    public void addNode(ResourceKey<Level> dim, BlockPos pos, String name) {
        graph(dim).nodes.put(pos.immutable(), name == null ? "" : name.trim());
        setDirty();
    }

    public void removeNode(ResourceKey<Level> dim, BlockPos pos) {
        Graph g = graph(dim);
        g.nodes.remove(pos);
        Set<BlockPos> ns = g.links.remove(pos);
        if (ns != null) {
            ns.forEach(n -> g.links.getOrDefault(n, new HashSet<>()).remove(pos));
        }
        g.runwayLinks.remove(pos);
        setDirty();
    }

    /** Runway marker removed: drop links to it. */
    public void removeMarker(ResourceKey<Level> dim, BlockPos marker) {
        Graph g = graph(dim);
        g.runwayLinks.values().forEach(s -> s.remove(marker));
        setDirty();
    }

    /** Link or unlink two waypoints. Returns true if they are now linked. */
    public boolean toggleLink(ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        Graph g = graph(dim);
        Set<BlockPos> la = g.links.computeIfAbsent(a.immutable(), k -> new HashSet<>());
        Set<BlockPos> lb = g.links.computeIfAbsent(b.immutable(), k -> new HashSet<>());
        boolean linked;
        if (la.contains(b)) {
            la.remove(b);
            lb.remove(a);
            linked = false;
        } else {
            la.add(b.immutable());
            lb.add(a.immutable());
            linked = true;
        }
        setDirty();
        return linked;
    }

    /** Link two waypoints (no-op if already linked). True if a new link was made. */
    public boolean link(ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        Graph g = graph(dim);
        boolean added = g.links.computeIfAbsent(a.immutable(), k -> new HashSet<>()).add(b.immutable());
        g.links.computeIfAbsent(b.immutable(), k -> new HashSet<>()).add(a.immutable());
        setDirty();
        return added;
    }

    public void unlink(ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        Graph g = graph(dim);
        g.links.getOrDefault(a, new HashSet<>()).remove(b);
        g.links.getOrDefault(b, new HashSet<>()).remove(a);
        setDirty();
    }

    /** Make a waypoint a runway entrance at this marker (no-op if already). */
    public void linkRunway(ResourceKey<Level> dim, BlockPos node, BlockPos marker) {
        graph(dim).runwayLinks.computeIfAbsent(node.immutable(), k -> new HashSet<>()).add(marker.immutable());
        setDirty();
    }

    /** Link or unlink a waypoint and a runway marker. Returns true if now linked. */
    public boolean toggleRunwayLink(ResourceKey<Level> dim, BlockPos node, BlockPos marker) {
        Set<BlockPos> set = graph(dim).runwayLinks.computeIfAbsent(node.immutable(), k -> new HashSet<>());
        boolean linked = !set.remove(marker);
        if (linked) {
            set.add(marker.immutable());
        }
        setDirty();
        return linked;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag dims = new ListTag();
        graphs.forEach((dim, g) -> {
            CompoundTag d = new CompoundTag();
            d.putString("dim", dim);
            ListTag nodes = new ListTag();
            g.nodes.forEach((pos, name) -> {
                CompoundTag n = NbtUtils.writeBlockPos(pos);
                n.putString("name", name);
                nodes.add(n);
            });
            d.put("nodes", nodes);
            d.put("links", pairs(g.linkList()));
            d.put("runwayLinks", pairs(g.runwayLinkList()));
            dims.add(d);
        });
        tag.put("dims", dims);
        return tag;
    }

    private static ListTag pairs(List<BlockPos[]> list) {
        ListTag out = new ListTag();
        for (BlockPos[] p : list) {
            CompoundTag t = new CompoundTag();
            t.put("a", NbtUtils.writeBlockPos(p[0]));
            t.put("b", NbtUtils.writeBlockPos(p[1]));
            out.add(t);
        }
        return out;
    }

    private static TaxiNetwork load(CompoundTag tag) {
        TaxiNetwork net = new TaxiNetwork();
        for (Tag t : tag.getList("dims", Tag.TAG_COMPOUND)) {
            CompoundTag d = (CompoundTag) t;
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(d.getString("dim")));
            Graph g = net.graph(dim);
            for (Tag n : d.getList("nodes", Tag.TAG_COMPOUND)) {
                g.nodes.put(NbtUtils.readBlockPos((CompoundTag) n), ((CompoundTag) n).getString("name"));
            }
            for (Tag l : d.getList("links", Tag.TAG_COMPOUND)) {
                BlockPos a = NbtUtils.readBlockPos(((CompoundTag) l).getCompound("a")), b = NbtUtils.readBlockPos(((CompoundTag) l).getCompound("b"));
                g.links.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                g.links.computeIfAbsent(b, k -> new HashSet<>()).add(a);
            }
            for (Tag l : d.getList("runwayLinks", Tag.TAG_COMPOUND)) {
                BlockPos a = NbtUtils.readBlockPos(((CompoundTag) l).getCompound("a")), b = NbtUtils.readBlockPos(((CompoundTag) l).getCompound("b"));
                g.runwayLinks.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            }
        }
        return net;
    }
}
