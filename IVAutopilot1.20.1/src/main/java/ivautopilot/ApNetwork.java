package ivautopilot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

public final class ApNetwork {
    private static final String VERSION = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IvAutopilot.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    public enum Action { CYCLE_MODE, TOGGLE_AUTO_TRIM, TARGET_UP, TARGET_DOWN }

    /** Client -> server: a pilot pressed an autopilot key. */
    public record Command(Action action) {
        void encode(FriendlyByteBuf buf) {
            buf.writeEnum(action);
        }

        static Command decode(FriendlyByteBuf buf) {
            return new Command(buf.readEnum(Action.class));
        }
    }

    /** Server -> client: autopilot state of the aircraft the player is riding. */
    public record Status(UUID aircraft, int mode, boolean autoTrim, float targetPitch, float targetAltitude, float targetSpeed, String info, String runway) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUUID(aircraft);
            buf.writeVarInt(mode);
            buf.writeBoolean(autoTrim);
            buf.writeFloat(targetPitch);
            buf.writeFloat(targetAltitude);
            buf.writeFloat(targetSpeed);
            buf.writeUtf(info, 256);
            buf.writeUtf(runway, 256);
        }

        static Status decode(FriendlyByteBuf buf) {
            return new Status(buf.readUUID(), buf.readVarInt(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf(256), buf.readUtf(256));
        }
    }

    public static void init() {
        CHANNEL.messageBuilder(Command.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Command::encode).decoder(Command::decode)
                .consumerMainThread(ApNetwork::handleCommand).add();
        CHANNEL.messageBuilder(Status.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Status::encode).decoder(Status::decode)
                .consumerMainThread(ApNetwork::handleStatus).add();
        CHANNEL.messageBuilder(Tracker.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Tracker::encode).decoder(Tracker::decode)
                .consumerMainThread(ApNetwork::handleTracker).add();
        CHANNEL.messageBuilder(ivautopilot.taxi.TaxiSync.Packet.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ivautopilot.taxi.TaxiSync.Packet::encode).decoder(ivautopilot.taxi.TaxiSync.Packet::decode)
                .consumerMainThread((msg, ctx) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ivautopilot.taxi.TaxiClient.onNetwork(msg)))
                .add();
    }

    /** Server -> client, once a second: every aircraft for the plane tracker (see track.AircraftTracker). */
    public record Tracker(java.util.List<ivautopilot.track.TrackedAircraft> aircraft) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(aircraft.size());
            aircraft.forEach(a -> a.encode(buf));
        }

        static Tracker decode(FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 1000);
            java.util.List<ivautopilot.track.TrackedAircraft> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(ivautopilot.track.TrackedAircraft.decode(buf));
            }
            return new Tracker(list);
        }
    }

    private static void handleTracker(Tracker msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ivautopilot.track.TrackerClient.update(msg.aircraft()));
    }

    private static void handleCommand(Command msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ApServer.handleCommand(player, msg.action());
        }
    }

    private static void handleStatus(Status msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ApClient.onStatus(msg));
    }

    private ApNetwork() {
    }
}
