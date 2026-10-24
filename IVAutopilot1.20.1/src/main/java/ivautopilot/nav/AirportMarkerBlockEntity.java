package ivautopilot.nav;

import ivautopilot.ApRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** An Airport Marker (one corner of an airport): its airport's name, and a status line for the floating label. */
public class AirportMarkerBlockEntity extends BlockEntity {
    private String airportName = "";
    private String info = "";

    public AirportMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(ApRegistry.AIRPORT_MARKER_BE.get(), pos, state);
    }

    public String getAirportName() {
        return airportName;
    }

    public String getInfo() {
        return info;
    }

    public void set(String name, String info) {
        this.airportName = name == null ? "" : name;
        this.info = info == null ? "" : info;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("airport", airportName);
        tag.putString("info", info);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        airportName = tag.getString("airport");
        info = tag.getString("info");
    }
}
