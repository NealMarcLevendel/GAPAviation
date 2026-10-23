package ivautopilot.track;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * One aircraft as shown by the plane tracker.
 *
 * @param bearing compass heading, degrees (0 = north, 90 = east)
 * @param status  "AUTOPILOT" (flying to a runway), "AP PITCH" / "AP ALT", "MANUAL" or "PARKED"
 * @param live    false = not currently loaded; position is where it was last seen
 * @param path    planned path as x,z pairs (empty if none), starting at the aircraft
 * @param runway  destination runway as x1,z1,x2,z2 (empty if none)
 */
public record TrackedAircraft(UUID id, String name, String dimension, float x, float y, float z, float bearing, float speedKmh,
                              String status, String phase, String destination, float etaSeconds, boolean live,
                              float[] path, float[] runway) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name, 128);
        buf.writeUtf(dimension, 128);
        buf.writeFloat(x);
        buf.writeFloat(y);
        buf.writeFloat(z);
        buf.writeFloat(bearing);
        buf.writeFloat(speedKmh);
        buf.writeUtf(status, 32);
        buf.writeUtf(phase, 32);
        buf.writeUtf(destination, 128);
        buf.writeFloat(etaSeconds);
        buf.writeBoolean(live);
        writeFloats(buf, path);
        writeFloats(buf, runway);
    }

    public static TrackedAircraft decode(FriendlyByteBuf buf) {
        return new TrackedAircraft(buf.readUUID(), buf.readUtf(128), buf.readUtf(128), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readUtf(32), buf.readUtf(32), buf.readUtf(128), buf.readFloat(), buf.readBoolean(),
                readFloats(buf), readFloats(buf));
    }

    private static void writeFloats(FriendlyByteBuf buf, float[] values) {
        buf.writeVarInt(values.length);
        for (float v : values) {
            buf.writeFloat(v);
        }
    }

    private static float[] readFloats(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 4096);
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            values[i] = buf.readFloat();
        }
        return values;
    }
}
