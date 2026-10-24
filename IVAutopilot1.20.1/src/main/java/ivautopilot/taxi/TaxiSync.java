package ivautopilot.taxi;

import ivautopilot.ApNetwork;
import ivautopilot.IvAutopilot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends each player the taxiway network of their dimension (for drawing it while holding the wand). */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TaxiSync {
    private TaxiSync() {
    }

    /** The packet: one dimension's waypoints (with names), links and runway links. */
    public record Packet(Map<BlockPos, String> nodes, List<BlockPos[]> links, List<BlockPos[]> runwayLinks) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(nodes.size());
            nodes.forEach((pos, name) -> {
                buf.writeBlockPos(pos);
                buf.writeUtf(name, 128);
            });
            writePairs(buf, links);
            writePairs(buf, runwayLinks);
        }

        public static Packet decode(FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 100000);
            Map<BlockPos, String> nodes = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                nodes.put(buf.readBlockPos(), buf.readUtf(128));
            }
            return new Packet(nodes, readPairs(buf), readPairs(buf));
        }

        private static void writePairs(FriendlyByteBuf buf, List<BlockPos[]> pairs) {
            buf.writeVarInt(pairs.size());
            for (BlockPos[] p : pairs) {
                buf.writeBlockPos(p[0]);
                buf.writeBlockPos(p[1]);
            }
        }

        private static List<BlockPos[]> readPairs(FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 200000);
            List<BlockPos[]> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new BlockPos[]{buf.readBlockPos(), buf.readBlockPos()});
            }
            return out;
        }
    }

    static Packet packetFor(ServerLevel level) {
        TaxiNetwork.Graph g = TaxiNetwork.get(level).graph(level.dimension());
        return new Packet(new LinkedHashMap<>(g.nodes), g.linkList(), g.runwayLinkList());
    }

    /** After a change: everyone in that dimension. */
    public static void broadcast(ServerLevel level) {
        ApNetwork.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), packetFor(level));
    }

    private static void send(ServerPlayer player) {
        ApNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packetFor(player.serverLevel()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player);
        }
    }
}
