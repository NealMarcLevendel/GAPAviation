package ivautopilot.taxi;

import ivautopilot.ApRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A taxi waypoint's name (empty = plain waypoint; named = gate / stand). */
public class TaxiWaypointBlockEntity extends BlockEntity {
    private String name = "";

    public TaxiWaypointBlockEntity(BlockPos pos, BlockState state) {
        super(ApRegistry.TAXI_WAYPOINT_BE.get(), pos, state);
    }

    public String getWaypointName() {
        return name;
    }

    public void setWaypointName(String name) {
        this.name = name == null ? "" : name.trim();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("name", name);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        name = tag.getString("name");
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
