package ivautopilot.nav;

import ivautopilot.ApRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** An airport marker's name and no-bank radius. */
public class AirportMarkerBlockEntity extends BlockEntity {
    public static final int DEFAULT_RADIUS = 300, MIN_RADIUS = 50, MAX_RADIUS = 3000, STEP = 50;
    private String airportName = "";
    private int radius = DEFAULT_RADIUS;

    public AirportMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(ApRegistry.AIRPORT_MARKER_BE.get(), pos, state);
    }

    public String getAirportName() {
        return airportName;
    }

    public int getRadius() {
        return radius;
    }

    public void set(String name, int radius) {
        this.airportName = name == null ? "" : name;
        this.radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        sync();
    }

    // Sent to clients so the floating label can show it.
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }


    public Airport airport() {
        return new Airport(airportName.isEmpty() ? "Airport" : airportName, getBlockPos(), radius);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("airport", airportName);
        tag.putInt("radius", radius);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        airportName = tag.getString("airport");
        radius = tag.contains("radius") ? tag.getInt("radius") : DEFAULT_RADIUS;
    }
}
