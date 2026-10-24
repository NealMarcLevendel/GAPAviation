package ivautopilot.taxi;

import ivautopilot.ApConfig;
import ivautopilot.ApRegistry;
import ivautopilot.nav.Runway;
import ivautopilot.nav.RunwayRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds taxi networks without the wand: /genwaypoints (waypoints along the centerlines of the
 * taxiway surface around the player, linked, with runway entrances), and auto-linking of
 * waypoints and terminal gates as they're placed.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = ivautopilot.IvAutopilot.MODID, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE)
public final class WaypointGenerator {
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            tick();
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        cancel();
    }

    public record Generated(int placed, int reused, int links, int entrances, int terminals, int bridges, int networks, int failed) {
    }

    /** How far up or down from the player's ground level a taxiway surface may be. */
    private static final int HEIGHT_TOLERANCE = 6;
    /** Separate pieces of network closer than this get joined (if a plane could taxi straight across). */
    private static final double BRIDGE_DISTANCE = 48;
    /** Paving beside a runway (shoulders) left out with the runway, so it doesn't become a taxiway running alongside. */
    private static final double RUNWAY_SHOULDER = 10;

    private WaypointGenerator() {
    }

    // ---------------------------------------------------------------------------------------------
    // /genwaypoints: a background job. The area is read a few chunks per tick (no lag spike, and no
    // pile of loaded chunks: each one is read once and left to unload), into bit sets (1 bit per
    // block column). The centerline maths runs on a worker thread; the blocks are placed back on
    // the server thread at the end.
    // ---------------------------------------------------------------------------------------------

    /** Largest area side, blocks (8192 x 8192 = 8 MB per bit grid; the work needs about seven). */
    public static final int MAX_SIDE = 8192;
    private static final long TICK_BUDGET_NANOS = 15_000_000L;
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IV Autopilot waypoint generator");
        t.setDaemon(true);
        return t;
    });
    @Nullable
    private static Job job;

    public static boolean busy() {
        return job != null;
    }

    /**
     * Start generating over the rectangle minX..maxX, minZ..maxZ (inclusive), reading the ground at
     * about groundY. Progress and the result go to `report`.
     */
    public static void start(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int groundY, Set<Block> surface, double spacing,
                             @Nullable net.minecraft.server.level.ServerPlayer viewer,
                             java.util.function.Consumer<net.minecraft.network.chat.Component> report) {
        job = new Job(level, minX, minZ, maxX - minX + 1, maxZ - minZ + 1, groundY, surface, spacing, viewer, report);
    }

    public static void cancel() {
        Job j = job;
        job = null;
        if (j != null) {
            j.bar.removeAllPlayers();
        }
    }

    /** Called every server tick. */
    public static void tick() {
        Job j = job;
        if (j != null && j.tick()) {
            job = null;
        }
    }

    private static final class Job {
        final ServerLevel level;
        final int x0, z0, w, h;
        /** Ground level being read (taxiway surface y +- HEIGHT_TOLERANCE); corrected after the first pass if most of the surface is elsewhere. */
        int groundY;
        /** How many surface blocks were seen at each height (index y - minBuildHeight), to find the real ground level. */
        final int[] heights;
        boolean reread;
        final Set<Block> surface;
        final double spacing;
        final java.util.function.Consumer<net.minecraft.network.chat.Component> report;
        final java.util.BitSet mask = new java.util.BitSet(), band = new java.util.BitSet();
        /** For the map image: surface blocks left out because they were too far above/below ground level. */
        final java.util.BitSet offLevel = new java.util.BitSet();
        /** Cells with something standing on the ground (lamp posts, signs, fences, walls, buildings): kept clear of. */
        final java.util.BitSet obstacle = new java.util.BitSet();
        /** Flat man-made ground-level blocks not in the list (painted markings): taxiway where they adjoin it. */
        final java.util.BitSet markings = new java.util.BitSet();
        /** Block types paving the ground level (first pass), for the summary and to point out ones missing from the list. */
        final java.util.Map<Block, Integer> paving = new java.util.HashMap<>();
        final List<Runway> runways;
        final double[] halfWidths;
        final int cx0, cz0, chunksX, chunksZ;
        int nextChunk;
        long lastReport = System.currentTimeMillis();
        /** Progress bar at the top of the screen (a boss bar) for the player who ran the command. */
        final net.minecraft.server.level.ServerBossEvent bar;
        @Nullable
        final net.minecraft.server.level.ServerPlayer viewer;
        /** Layout progress 0..1, written by the worker thread. */
        volatile double layoutProgress;
        /** Map image of what was found (drawn by the worker), and where it was saved. */
        volatile DebugMap map;
        @Nullable
        String mapFile;
        @Nullable
        java.util.concurrent.CompletableFuture<SkeletonGraph.Result> graph;

        Job(ServerLevel level, int x0, int z0, int w, int h, int groundY, Set<Block> surface, double spacing,
            @Nullable net.minecraft.server.level.ServerPlayer viewer,
            java.util.function.Consumer<net.minecraft.network.chat.Component> report) {
            this.level = level;
            this.viewer = viewer;
            this.bar = new net.minecraft.server.level.ServerBossEvent(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_bar_reading", 0),
                    net.minecraft.world.BossEvent.BossBarColor.YELLOW, net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_20);
            bar.setProgress(0);
            if (viewer != null) {
                bar.addPlayer(viewer);
            }
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.h = h;
            this.groundY = groundY;
            this.heights = new int[level.getHeight()];
            this.surface = surface;
            this.spacing = spacing;
            this.report = report;
            this.runways = new ArrayList<>();
            for (Runway r : RunwayRegistry.get(level).all(level.dimension())) {
                // Only runways that come into (or close to) the area matter.
                double d = distanceToRunway(r, x0 + w / 2.0, z0 + h / 2.0);
                if (d < Math.hypot(w, h) / 2 + 100) {
                    runways.add(r);
                }
            }
            this.halfWidths = new double[runways.size()];
            for (int i = 0; i < runways.size(); i++) {
                halfWidths[i] = runwayHalfWidth(level, runways.get(i), surface);
            }
            this.cx0 = x0 >> 4;
            this.cz0 = z0 >> 4;
            this.chunksX = ((x0 + w - 1) >> 4) - cx0 + 1;
            this.chunksZ = ((z0 + h - 1) >> 4) - cz0 + 1;
        }

        /** True when finished. */
        boolean tick() {
            if (graph == null) {
                long until = System.nanoTime() + TICK_BUDGET_NANOS;
                while (nextChunk < chunksX * chunksZ && System.nanoTime() < until) {
                    scanChunk(cx0 + nextChunk % chunksX, cz0 + nextChunk / chunksX);
                    nextChunk++;
                }
                int percent = 100 * nextChunk / (chunksX * chunksZ);
                // Reading the ground: first 75% of the bar.
                bar.setProgress(0.75F * nextChunk / (chunksX * chunksZ));
                bar.setName(net.minecraft.network.chat.Component.translatable(reread && groundYChanged() ? "msg.ivautopilot.gen_bar_rereading"
                        : "msg.ivautopilot.gen_bar_reading", percent));
                if (viewer == null && System.currentTimeMillis() - lastReport > 5000) {
                    lastReport = System.currentTimeMillis(); // console / command block: progress in chat instead
                    report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_progress", percent));
                }
                if (nextChunk >= chunksX * chunksZ && !reread) {
                    // Was that the right ground level? If most of the surface is at another height (the
                    // player stood on a roof or sat in a plane), read the area again at that height.
                    int best = bestLevel();
                    if (windowCount(best) > windowCount(groundY) * 1.5 && Math.abs(best - groundY) > HEIGHT_TOLERANCE / 2) {
                        reread = true;
                        int from = groundY;
                        groundY = best;
                        mask.clear();
                        band.clear();
                        offLevel.clear();
                        obstacle.clear();
                        markings.clear();
                        nextChunk = 0;
                        report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_relevel", from, best));
                        return false;
                    }
                    reread = true;
                }
                if (nextChunk >= chunksX * chunksZ) {
                    graph = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        SkeletonGraph.Result r = SkeletonGraph.build(w, h, mask, band, obstacle, markings, spacing, p -> layoutProgress = p);
                        map = DebugMap.surface(w, h, mask, band, offLevel, obstacle);
                        return r;
                    }, WORKER);
                }
                return false;
            }
            if (!graph.isDone()) {
                // Working out the taxiways: 75% .. 98%.
                bar.setProgress((float) (0.75 + 0.23 * layoutProgress));
                bar.setName(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_bar_layout"));
                return false;
            }
            bar.setProgress(1);
            bar.removeAllPlayers();
            try {
                Generated result = place(graph.join());
                report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_done", result.placed(), result.reused(),
                        result.links(), result.entrances(), result.terminals()));
                report.accept(net.minecraft.network.chat.Component.translatable(result.networks() <= 1 ? "msg.ivautopilot.gen_connected"
                        : "msg.ivautopilot.gen_pieces", result.networks(), result.bridges(), result.failed()));
                if (runways.isEmpty()) {
                    report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_no_runways"));
                }
                reportPaving();
                if (mapFile != null) {
                    report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_map", mapFile));
                }
            } catch (RuntimeException e) {
                ivautopilot.IvAutopilot.LOGGER.error("Waypoint generation failed", e);
                report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_failed", String.valueOf(e.getMessage())));
            }
            return true;
        }

        private int startY = Integer.MIN_VALUE;

        private boolean groundYChanged() {
            if (startY == Integer.MIN_VALUE) {
                startY = groundY;
            }
            return startY != groundY;
        }

        /** Surface blocks within HEIGHT_TOLERANCE of this height. */
        private int windowCount(int y) {
            int sum = 0;
            for (int dy = -HEIGHT_TOLERANCE; dy <= HEIGHT_TOLERANCE; dy++) {
                int i = y + dy - level.getMinBuildHeight();
                if (i >= 0 && i < heights.length) {
                    sum += heights[i];
                }
            }
            return sum;
        }

        /** The height whose window holds the most surface blocks. */
        private int bestLevel() {
            int best = groundY, bestCount = -1;
            for (int i = 0; i < heights.length; i++) {
                if (heights[i] == 0) {
                    continue;
                }
                int y = i + level.getMinBuildHeight(), c = windowCount(y);
                if (c > bestCount) {
                    bestCount = c;
                    best = y;
                }
            }
            return best;
        }

        /** Tell the player what the ground is paved with, and which common paving blocks their list left out. */
        private void reportPaving() {
            int total = paving.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) {
                return;
            }
            List<java.util.Map.Entry<Block, Integer>> sorted = new ArrayList<>(paving.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder found = new StringBuilder(), missing = new StringBuilder();
            for (int k = 0; k < Math.min(6, sorted.size()); k++) {
                var e = sorted.get(k);
                int pct = (int) Math.round(100.0 * e.getValue() / total);
                if (pct < 1) {
                    break;
                }
                String id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(e.getKey())).replace("minecraft:", "");
                found.append(found.length() > 0 ? ", " : "").append(id).append(" ").append(pct).append("%");
                if (!surface.isEmpty() && !surface.contains(e.getKey()) && pct >= 5) {
                    missing.append(missing.length() > 0 ? "," : "").append(id);
                }
            }
            report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_paving", found.toString()));
            if (missing.length() > 0) {
                report.accept(net.minecraft.network.chat.Component.translatable("msg.ivautopilot.gen_missing", missing.toString()));
            }
        }

        /** Taxiway surface? The listed blocks - or, with no list, any paving (not natural ground). */
        private boolean accepts(BlockState state, boolean top) {
            if (!surface.isEmpty()) {
                return surface.contains(state.getBlock());
            }
            return !natural(state) && (!top || state.isSolidRender(level, BlockPos.ZERO) || state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock);
        }

        /** Flat enough to roll over (carpet, slab, snow, a pressure plate) or nothing to hit (a sign, a torch). */
        private boolean flat(BlockState state, net.minecraft.world.level.BlockGetter getter, BlockPos pos) {
            var shape = state.getCollisionShape(getter, pos);
            return shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) <= 0.5;
        }

        private boolean inArea(int x, int z) {
            return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h;
        }

        private void scanChunk(int cx, int cz) {
            int minX = Math.max(x0, cx << 4), maxX = Math.min(x0 + w - 1, (cx << 4) + 15);
            int minZ = Math.max(z0, cz << 4), maxZ = Math.min(z0 + h - 1, (cz << 4) + 15);
            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(cx, cz);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!inArea(x, z)) {
                        continue;
                    }
                    int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                    int i = (x - x0) + (z - z0) * w;
                    BlockState topState = chunk.getBlockState(p.set(x, y, z));
                    BlockState ground = topState;
                    boolean isSurface = accepts(topState, true);
                    boolean flatTop = flat(topState, chunk, p);
                    if (!isSurface && flatTop) {
                        // Something thin on top (carpet markings, a slab, a light, snow, a plant): look at the block under it.
                        BlockState below = chunk.getBlockState(p.set(x, y - 1, z));
                        ground = below;
                        if (accepts(below, false)) {
                            isSurface = true;
                            y--;
                        }
                    }
                    if (!reread && Math.abs(y - groundY) <= HEIGHT_TOLERANCE && !natural(ground)) {
                        paving.merge(ground.getBlock(), 1, Integer::sum); // what the ground level is paved with (for the summary)
                    }
                    if (isSurface && !reread) {
                        int hi = y - level.getMinBuildHeight();
                        if (hi >= 0 && hi < heights.length) {
                            heights[hi]++;
                        }
                    }
                    if (!isSurface && !flatTop && y >= groundY + 1 && y <= groundY + 40) {
                        obstacle.set(i); // something standing on the ground (or a building) - not a gap to bridge
                    } else if (!isSurface && y > groundY + 40) {
                        obstacle.set(i);
                    }
                    if (Math.abs(y - groundY) > HEIGHT_TOLERANCE) {
                        if (isSurface) {
                            offLevel.set(i);
                            obstacle.set(i); // a raised surface (a roof, a deck) is in the way too
                        }
                        continue;
                    }
                    boolean onRunway = false;
                    for (int r = 0; r < runways.size() && !onRunway; r++) {
                        onRunway = inRunway(runways.get(r), halfWidths[r] + RUNWAY_SHOULDER, x + 0.5, y, z + 0.5);
                    }
                    if (onRunway) {
                        band.set(i);
                    } else if (isSurface) {
                        mask.set(i);
                    } else if (!obstacle.get(i) && !natural(ground)) {
                        markings.set(i);
                    }
                }
            }
        }

        private Generated place(SkeletonGraph.Result result) {
            TaxiNetwork network = TaxiNetwork.get(level);
            TaxiNetwork.Graph g = network.graph(level.dimension());
            BlockPos[] placedAt = new BlockPos[result.nodes().size()];
            int placed = 0, reused = 0, failed = 0;
            int[] degree = new int[result.nodes().size()];
            for (int[] l : result.links()) {
                degree[l[0]]++;
                degree[l[1]]++;
            }
            for (int n = 0; n < result.nodes().size(); n++) {
                int[] c = result.nodes().get(n);
                int x = x0 + c[0], z = z0 + c[1];
                if (degree[n] == 0) {
                    continue; // a stray point with no taxiway to it
                }
                // Ground height from the chunk itself (loading it if needed): Level.getHeight answers the
                // bottom of the world for a chunk that isn't loaded, which put waypoints at y -64.
                int top = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                BlockPos at = new BlockPos(x, top + 1, z);
                BlockPos existing = nearestNode(g, at, 3);
                if (existing != null) {
                    placedAt[n] = existing;
                    reused++;
                    continue;
                }
                BlockState here = level.getBlockState(at);
                if (!here.isAir() && !here.canBeReplaced()) {
                    failed++;
                    continue;
                }
                level.setBlock(at, ApRegistry.TAXI_WAYPOINT.get().defaultBlockState(), 3);
                network.addNode(level.dimension(), at, "");
                placedAt[n] = at;
                placed++;
            }
            int links = 0;
            for (int[] l : result.links()) {
                BlockPos a = placedAt[l[0]], b = placedAt[l[1]];
                if (a != null && b != null && !a.equals(b) && network.link(level.dimension(), a, b)) {
                    links++;
                }
            }
            // Dead ends at the runway: runway entrances/exits (both ends, so either takeoff direction works).
            int entrances = 0;
            RunwayRegistry registry = RunwayRegistry.get(level);
            for (int n : result.runwayEnds()) {
                BlockPos at = placedAt[n];
                Runway runway = at == null ? null : nearestRunway(runways, halfWidths, at, RUNWAY_SHOULDER + 10);
                if (runway != null) {
                    for (BlockPos marker : registry.markers(level.dimension(), runway.name())) {
                        network.linkRunway(level.dimension(), at, marker);
                    }
                    entrances++;
                }
            }
            entrances += linkEntrances(level, x0, z0, x0 + w - 1, z0 + h - 1);
            // Terminal gates already in the area: hook each onto the nearest waypoint.
            int terminals = 0;
            for (BlockPos node : new ArrayList<>(g.nodes.keySet())) {
                if (inArea(node.getX(), node.getZ()) && level.isLoaded(node)
                        && level.getBlockState(node).getBlock() instanceof TerminalBlock && g.neighbours(node).isEmpty()) {
                    if (autoLink(level, node, true) > 0) {
                        terminals++;
                    }
                }
            }
            // Join pieces that ended up separate (a gap the surface scan couldn't bridge, a node that
            // couldn't be placed): shortest clear straight links first, until nothing more joins.
            List<BlockPos> ours = new ArrayList<>();
            for (BlockPos b : placedAt) {
                if (b != null) {
                    ours.add(b);
                }
            }
            int bridges = bridge(level, network, g, ours);
            int networks = countNetworks(g, ours);
            if (map != null) {
                for (int[] l : result.links()) {
                    BlockPos a = placedAt[l[0]], b = placedAt[l[1]];
                    int[] ca = result.nodes().get(l[0]), cb = result.nodes().get(l[1]);
                    map.line(ca[0], ca[1], cb[0], cb[1], a != null && b != null ? 0xFFD000 : 0xFF00FF);
                }
                for (int n = 0; n < result.nodes().size(); n++) {
                    int[] c = result.nodes().get(n);
                    map.dot(c[0], c[1], placedAt[n] != null ? 0xFF2020 : 0xFF00FF);
                }
                for (BlockPos[] l : g.linkList()) {
                    if (inArea(l[0].getX(), l[0].getZ()) && inArea(l[1].getX(), l[1].getZ())) {
                        // Bridges and older links in cyan (drawn over, so new pieces' links stay yellow underneath).
                        boolean generated = false;
                        for (int[] gl : result.links()) {
                            if (placedAt[gl[0]] != null && placedAt[gl[1]] != null
                                    && (placedAt[gl[0]].equals(l[0]) && placedAt[gl[1]].equals(l[1]) || placedAt[gl[0]].equals(l[1]) && placedAt[gl[1]].equals(l[0]))) {
                                generated = true;
                                break;
                            }
                        }
                        if (!generated) {
                            map.line(l[0].getX() - x0, l[0].getZ() - z0, l[1].getX() - x0, l[1].getZ() - z0, 0x00E0FF);
                        }
                    }
                }
                mapFile = map.save(level.getServer().getServerDirectory().toPath().resolve("ivautopilot-taxi-map.png"));
            }
            TaxiSync.broadcast(level);
            return new Generated(placed, reused, links, entrances, terminals, bridges, networks, failed);
        }
    }

    /**
     * Join separate pieces: candidate links between waypoints of different pieces within
     * BRIDGE_DISTANCE (found with a coarse grid, not every pair), shortest first, kept if a plane
     * could taxi straight across. Returns the number of links added.
     */
    private static int bridge(ServerLevel level, TaxiNetwork network, TaxiNetwork.Graph g, List<BlockPos> nodes) {
        java.util.Map<BlockPos, Integer> piece = pieces(g, nodes);
        int[] parent = new int[nodes.size() + 1];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        java.util.Map<Long, List<BlockPos>> grid = new java.util.HashMap<>();
        int cell = (int) BRIDGE_DISTANCE;
        for (BlockPos n : nodes) {
            grid.computeIfAbsent(cellKey(Math.floorDiv(n.getX(), cell), Math.floorDiv(n.getZ(), cell)), k -> new ArrayList<>()).add(n);
        }
        List<BlockPos[]> candidates = new ArrayList<>();
        for (BlockPos n : nodes) {
            int gx = Math.floorDiv(n.getX(), cell), gz = Math.floorDiv(n.getZ(), cell);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (BlockPos m : grid.getOrDefault(cellKey(gx + dx, gz + dz), List.of())) {
                        if (n.asLong() < m.asLong() && !piece.get(n).equals(piece.get(m))
                                && n.distSqr(m) <= BRIDGE_DISTANCE * BRIDGE_DISTANCE && Math.abs(n.getY() - m.getY()) <= 3) {
                            candidates.add(new BlockPos[]{n, m});
                        }
                    }
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a[0].distSqr(a[1]), b[0].distSqr(b[1])));
        int added = 0;
        for (BlockPos[] c : candidates) {
            int a = find(parent, piece.get(c[0])), b = find(parent, piece.get(c[1]));
            if (a != b && clearPath(level, c[0], c[1])) {
                network.link(level.dimension(), c[0], c[1]);
                parent[a] = b;
                added++;
            }
        }
        return added;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Which connected piece of the network each of these waypoints is in (numbered from 0). */
    private static java.util.Map<BlockPos, Integer> pieces(TaxiNetwork.Graph g, List<BlockPos> nodes) {
        java.util.Map<BlockPos, Integer> piece = new java.util.HashMap<>();
        int next = 0;
        for (BlockPos start : nodes) {
            if (piece.containsKey(start)) {
                continue;
            }
            java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            piece.put(start, next);
            while (!queue.isEmpty()) {
                for (BlockPos n : g.neighbours(queue.poll())) {
                    if (piece.putIfAbsent(n, next) == null) {
                        queue.add(n);
                    }
                }
            }
            next++;
        }
        return piece;
    }

    private static int countNetworks(TaxiNetwork.Graph g, List<BlockPos> nodes) {
        java.util.Map<BlockPos, Integer> piece = pieces(g, nodes);
        Set<Integer> distinct = new HashSet<>();
        for (BlockPos n : nodes) {
            distinct.add(piece.get(n));
        }
        return distinct.size();
    }

    private static double distanceToRunway(Runway r, double x, double z) {
        Vec3 dir = Runway.heading(r.a(), r.b());
        double along = Math.max(0, Math.min(r.length(), (x - r.a().x) * dir.x + (z - r.a().z) * dir.z));
        return Math.hypot(r.a().x + dir.x * along - x, r.a().z + dir.z * along - z);
    }

    /**
     * Runway entrances: every taxiway dead end within reach of a runway (its paving and shoulders,
     * plus 25 blocks - the generated centerlines stop about half a taxiway's width short of the
     * runway) becomes an entrance/exit for both ends of it. Only waypoints inside the given area
     * (null = everywhere). Returns how many waypoints were made entrances.
     */
    public static int linkEntrances(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        TaxiNetwork network = TaxiNetwork.get(level);
        TaxiNetwork.Graph g = network.graph(level.dimension());
        RunwayRegistry registry = RunwayRegistry.get(level);
        int count = 0;
        for (Runway runway : registry.all(level.dimension())) {
            double half = runwayHalfWidth(level, runway, Set.of());
            double reach = half + RUNWAY_SHOULDER + 25;
            List<BlockPos> markers = registry.markers(level.dimension(), runway.name());
            for (BlockPos node : new ArrayList<>(g.nodes.keySet())) {
                if (node.getX() < minX || node.getX() > maxX || node.getZ() < minZ || node.getZ() > maxZ
                        || g.neighbours(node).size() > 1 || !g.nodes.get(node).isEmpty()) {
                    continue;
                }
                double x = node.getX() + 0.5, z = node.getZ() + 0.5;
                if (inRunway(runway, reach, x, node.getY() - 1, z) && !inRunway(runway, half, x, node.getY() - 1, z)) {
                    boolean added = false;
                    for (BlockPos marker : markers) {
                        if (!g.runwayLinks.getOrDefault(node, Set.of()).contains(marker)) {
                            network.linkRunway(level.dimension(), node, marker);
                            added = true;
                        }
                    }
                    if (added) {
                        count++;
                    }
                }
            }
        }
        TaxiSync.broadcast(level);
        return count;
    }

    /** Remove the plain taxi waypoints (not terminal gates) within the radius. Returns how many. */
    public static int clear(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        int removed = 0;
        for (BlockPos node : new ArrayList<>(g.nodes.keySet())) {
            if (node.getX() >= minX && node.getX() <= maxX && node.getZ() >= minZ && node.getZ() <= maxZ) {
                Block block = level.getBlockState(node).getBlock();
                if (block instanceof TaxiWaypointBlock && !(block instanceof TerminalBlock)) {
                    level.removeBlock(node, false);
                    removed++;
                } else if (!(block instanceof TaxiWaypointBlock)) {
                    TaxiNetwork.get(level).removeNode(level.dimension(), node); // stale entry
                }
            }
        }
        TaxiSync.broadcast(level);
        return removed;
    }

    // ---------------------------------------------------------------------------------------------
    // Runways
    // ---------------------------------------------------------------------------------------------

    static boolean inRunway(Runway runway, double halfWidth, double x, double y, double z) {
        Vec3 dir = Runway.heading(runway.a(), runway.b());
        double along = (x - runway.a().x) * dir.x + (z - runway.a().z) * dir.z;
        double cross = Math.abs((x - runway.a().x) * dir.z - (z - runway.a().z) * dir.x);
        double surface = Math.min(runway.a().y, runway.b().y) - 1;
        return cross <= halfWidth && along >= -halfWidth && along <= runway.length() + halfWidth && Math.abs(y - surface) < 4;
    }

    /**
     * Half the runway's width: from its centerline, how far the runway surface (the centerline's own
     * block, or any of the given surface blocks) carries on to each side. Median of samples along it.
     */
    /** Natural ground (not paving): dirt/grass, sand, gravel, snow, water, plants, trees. */
    static boolean natural(BlockState state) {
        return state.isAir() || state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(net.minecraft.tags.BlockTags.SAND)
                || state.is(net.minecraft.world.level.block.Blocks.GRAVEL) || state.is(net.minecraft.world.level.block.Blocks.CLAY)
                || state.is(net.minecraft.tags.BlockTags.SNOW) || state.is(net.minecraft.tags.BlockTags.ICE)
                || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(net.minecraft.tags.BlockTags.LOGS)
                || state.is(net.minecraft.tags.BlockTags.REPLACEABLE) || state.is(net.minecraft.tags.BlockTags.FLOWERS)
                || !state.getFluidState().isEmpty();
    }

    public static double runwayHalfWidth(ServerLevel level, Runway runway, Set<Block> surface) {
        Vec3 dir = Runway.heading(runway.a(), runway.b());
        Vec3 side = new Vec3(dir.z, 0, -dir.x);
        int y = (int) Math.floor(Math.min(runway.a().y, runway.b().y)) - 1;
        List<Integer> widths = new ArrayList<>();
        for (int s = 1; s <= 9; s++) {
            double along = runway.length() * s / 10.0;
            double cx = runway.a().x + dir.x * along, cz = runway.a().z + dir.z * along;
            // Out from the centerline across the runway's paving and markings, to where the taxiway
            // surface (the listed blocks) or natural ground begins. (Loads the chunk if needed.)
            for (int sign = -1; sign <= 1; sign += 2) {
                int k = 1;
                for (; k <= 80; k++) {
                    BlockPos q = BlockPos.containing(cx + side.x * k * sign, y, cz + side.z * k * sign);
                    BlockState b = level.getBlockState(q);
                    if (natural(b) || surface.contains(b.getBlock())
                            || !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) {
                        break;
                    }
                }
                widths.add(k - 1);
            }
        }
        if (widths.isEmpty()) {
            return 12;
        }
        widths.sort(Integer::compare);
        return Math.max(3, Math.min(80, widths.get(widths.size() / 2)));
    }

    @Nullable
    private static Runway nearestRunway(List<Runway> runways, double[] halfWidths, BlockPos pos, double margin) {
        for (int r = 0; r < runways.size(); r++) {
            if (inRunway(runways.get(r), halfWidths[r] + margin, pos.getX() + 0.5, pos.getY() - 1, pos.getZ() + 0.5)) {
                return runways.get(r);
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos nearestNode(TaxiNetwork.Graph g, BlockPos at, double within) {
        BlockPos best = null;
        double bestD = within * within;
        for (BlockPos n : g.nodes.keySet()) {
            double dx = n.getX() - at.getX(), dz = n.getZ() - at.getZ();
            double d = dx * dx + dz * dz;
            if (d <= bestD && Math.abs(n.getY() - at.getY()) < 4) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------
    // Auto-linking on placement
    // ---------------------------------------------------------------------------------------------

    /**
     * Link a newly placed waypoint to its neighbours: the nearest waypoints within autoLinkDistance
     * that a plane could taxi straight to (flat, nothing in the way), at most one per direction (one
     * further away in the same direction is reached through the nearer one) - and if it lands on an
     * existing link, it splits that link. A terminal gate links to the single nearest one. Near a
     * runway edge it also becomes that runway's entrance. Returns the number of links made.
     */
    public static int autoLink(ServerLevel level, BlockPos pos, boolean terminal) {
        double maxDistance = ApConfig.AUTO_LINK_DISTANCE.get();
        if (maxDistance <= 0) {
            return 0;
        }
        TaxiNetwork network = TaxiNetwork.get(level);
        TaxiNetwork.Graph g = network.graph(level.dimension());
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos n : g.nodes.keySet()) {
            if (!n.equals(pos) && Math.abs(n.getY() - pos.getY()) <= 3 && n.distSqr(pos) <= maxDistance * maxDistance) {
                candidates.add(n);
            }
        }
        candidates.sort((a, b) -> Double.compare(a.distSqr(pos), b.distSqr(pos)));
        // Placed on (near) an existing link: split it.
        int made = 0;
        if (!terminal) {
            for (BlockPos[] link : g.linkList()) {
                if (distanceToSegment(pos, link[0], link[1]) < 3 && !pos.equals(link[0]) && !pos.equals(link[1])
                        && between(pos, link[0], link[1])) {
                    network.unlink(level.dimension(), link[0], link[1]);
                    network.link(level.dimension(), pos, link[0]);
                    network.link(level.dimension(), pos, link[1]);
                    TaxiSync.broadcast(level);
                    return 2;
                }
            }
        }
        List<BlockPos> accepted = new ArrayList<>();
        for (BlockPos c : candidates) {
            if (accepted.size() >= (terminal ? 1 : 4)) {
                break;
            }
            double angle = Math.atan2(c.getZ() - pos.getZ(), c.getX() - pos.getX());
            boolean shadowed = false;
            for (BlockPos a : accepted) {
                double other = Math.atan2(a.getZ() - pos.getZ(), a.getX() - pos.getX());
                double diff = Math.abs(Math.toDegrees(Math.atan2(Math.sin(angle - other), Math.cos(angle - other))));
                if (diff < 40) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed && clearPath(level, pos, c)) {
                accepted.add(c);
            }
        }
        for (BlockPos c : accepted) {
            if (network.link(level.dimension(), pos, c)) {
                made++;
            }
        }
        // Next to a runway: its entrance/exit.
        if (!terminal) {
            List<Runway> runways = RunwayRegistry.get(level).all(level.dimension());
            for (Runway runway : runways) {
                double half = runwayHalfWidth(level, runway, Set.of());
                if (inRunway(runway, half + 10, pos.getX() + 0.5, pos.getY() - 1, pos.getZ() + 0.5)) {
                    for (BlockPos marker : RunwayRegistry.get(level).markers(level.dimension(), runway.name())) {
                        network.linkRunway(level.dimension(), pos, marker);
                    }
                    break;
                }
            }
        }
        TaxiSync.broadcast(level);
        return made;
    }

    private static boolean between(BlockPos p, BlockPos a, BlockPos b) {
        double abx = b.getX() - a.getX(), abz = b.getZ() - a.getZ();
        double t = ((p.getX() - a.getX()) * abx + (p.getZ() - a.getZ()) * abz) / Math.max(1.0E-6, abx * abx + abz * abz);
        return t > 0.05 && t < 0.95;
    }

    private static double distanceToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double abx = b.getX() - a.getX(), abz = b.getZ() - a.getZ();
        double len2 = abx * abx + abz * abz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.getX() - a.getX()) * abx + (p.getZ() - a.getZ()) * abz) / len2));
        return Math.hypot(p.getX() - a.getX() - t * abx, p.getZ() - a.getZ() - t * abz);
    }

    /** A plane could roll straight from a to b: solid ground all the way, nothing standing on it, no steps over 1 block. */
    static boolean clearPath(ServerLevel level, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        int steps = (int) Math.ceil(Math.hypot(dx, dz));
        int y = a.getY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int s = 1; s < steps; s++) {
            int x = (int) Math.floor(a.getX() + 0.5 + dx * s / steps), z = (int) Math.floor(a.getZ() + 0.5 + dz * s / steps);
            boolean ok = false;
            for (int dy : new int[]{0, 1, -1}) {
                int yy = y + dy;
                if (!free(level, p.set(x, yy - 1, z))) {
                    // Room for a plane: nothing from the ground up to 6 blocks (tail, wings, jet bridges).
                    boolean clear = true;
                    for (int up = 0; up < 6 && clear; up++) {
                        clear = free(level, p.set(x, yy + up, z));
                    }
                    if (clear) {
                        y = yy;
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        return state.getCollisionShape(level, p).isEmpty() && state.getFluidState().isEmpty();
    }

    /** Next free automatic gate name: "Gate 1", "Gate 2", ... */
    public static String nextGateName(TaxiNetwork.Graph g) {
        Set<String> used = new HashSet<>();
        g.nodes.values().forEach(n -> used.add(n.toLowerCase(java.util.Locale.ROOT)));
        for (int i = 1; ; i++) {
            if (!used.contains("gate " + i)) {
                return "Gate " + i;
            }
        }
    }
}
