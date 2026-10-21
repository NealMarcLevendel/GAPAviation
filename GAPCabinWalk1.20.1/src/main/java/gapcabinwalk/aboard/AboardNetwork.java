package gapcabinwalk.aboard;

import gapcabinwalk.GapCabinWalk;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/** Server -> client: where another player is standing inside an aircraft (aircraft-relative). */
public final class AboardNetwork {
    private static final String VERSION = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GapCabinWalk.MODID, "aboard"), () -> VERSION, VERSION::equals, VERSION::equals);

    public static void init() {
        CHANNEL.messageBuilder(Sync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Sync::encode)
                .decoder(Sync::decode)
                .consumerMainThread(AboardNetwork::handleSync)
                .add();
        CHANNEL.messageBuilder(Report.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Report::encode)
                .decoder(Report::decode)
                .consumerMainThread(AboardNetwork::handleReport)
                .add();
        CHANNEL.messageBuilder(StandUp.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StandUp::encode)
                .decoder(StandUp::decode)
                .consumerMainThread(AboardNetwork::handleStandUp)
                .add();
    }

    /**
     * Server -> the player who just stood up: your spot in the aisle, in the aircraft's frame. The
     * client places itself there against ITS copy of the aircraft (which can be a tick or more
     * behind the server's - several blocks in flight), instead of trusting the server's world-space
     * teleport, which could land it in the floor, a wall or outside the hull.
     */
    public record StandUp(UUID vehicleId, float x, float y, float z) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUUID(vehicleId);
            buf.writeFloat(x);
            buf.writeFloat(y);
            buf.writeFloat(z);
        }

        static StandUp decode(FriendlyByteBuf buf) {
            return new StandUp(buf.readUUID(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    private static void handleStandUp(StandUp msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AboardClient.onStandUp(msg));
    }

    /**
     * Client -> server, every tick while aboard: "I'm aboard this aircraft, at this spot in its frame".
     * The client is the authority on this (it simulates the player against its own copy of the aircraft);
     * the server's copy of a flying aircraft can be a few blocks off, so it must not guess for itself.
     * vehicleId == null means "no longer aboard".
     */
    public record Report(@Nullable UUID vehicleId, float x, float y, float z) {
        void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(vehicleId != null);
            if (vehicleId != null) {
                buf.writeUUID(vehicleId);
                buf.writeFloat(x);
                buf.writeFloat(y);
                buf.writeFloat(z);
            }
        }

        static Report decode(FriendlyByteBuf buf) {
            if (buf.readBoolean()) {
                return new Report(buf.readUUID(), buf.readFloat(), buf.readFloat(), buf.readFloat());
            }
            return new Report(null, 0, 0, 0);
        }
    }

    private static void handleReport(Report msg, Supplier<NetworkEvent.Context> ctx) {
        net.minecraft.server.level.ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AboardServer.onReport(player, msg);
        }
    }

    /** vehicleId == null means "no longer aboard". */
    public record Sync(int entityId, @Nullable UUID vehicleId, float x, float y, float z) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(entityId);
            buf.writeBoolean(vehicleId != null);
            if (vehicleId != null) {
                buf.writeUUID(vehicleId);
                buf.writeFloat(x);
                buf.writeFloat(y);
                buf.writeFloat(z);
            }
        }

        static Sync decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            if (buf.readBoolean()) {
                return new Sync(id, buf.readUUID(), buf.readFloat(), buf.readFloat(), buf.readFloat());
            }
            return new Sync(id, null, 0, 0, 0);
        }
    }

    private static void handleSync(Sync msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AboardClient.onSync(msg));
    }

    private AboardNetwork() {
    }
}
