package ivautopilot.nav;

import ivautopilot.ApRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Remembers which runway a marker belongs to. */
public class RunwayMarkerBlockEntity extends BlockEntity {
    private String runwayName = "";
    /** Set by the server: e.g. "heading 090, 820 blocks" or "1 of 2 markers". */
    private String info = "";

    public RunwayMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(ApRegistry.RUNWAY_MARKER_BE.get(), pos, state);
    }

    public String getRunwayName() {
        return runwayName;
    }

    public void setRunwayName(String name) {
        runwayName = name == null ? "" : name;
        sync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Markers placed before labels existed, or whose partner changed while unloaded: fill the label in.
        if (level instanceof net.minecraft.server.level.ServerLevel server && !runwayName.isEmpty()) {
            info = RunwayRegistry.get(server).labelInfo(server.dimension(), runwayName, worldPosition);
        }
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        if (!this.info.equals(info)) {
            this.info = info;
            sync();
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

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }


    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("runway", runwayName);
        tag.putString("info", info);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        runwayName = tag.getString("runway");
        info = tag.getString("info");
    }
}
