package ivautopilot.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Marks one end of a runway. Rename the marker in an anvil (e.g. "KJFK 09") and place two markers with
 * the same name, one at each end of the runway centerline: together they define the runway's
 * position, length, direction and elevation. Right-click a marker to see its runway.
 */
public class RunwayMarkerBlock extends BaseEntityBlock {
    public RunwayMarkerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RunwayMarkerBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server) {
            String name = stack.hasCustomHoverName() ? stack.getHoverName().getString().trim() : "";
            if (level.getBlockEntity(pos) instanceof RunwayMarkerBlockEntity marker) {
                marker.setRunwayName(name);
            }
            if (name.isEmpty()) {
                if (placer instanceof Player player) {
                    player.displayClientMessage(Component.translatable("msg.ivautopilot.runway_unnamed"), false);
                }
                return;
            }
            RunwayRegistry registry = RunwayRegistry.get(server);
            registry.addMarker(server.dimension(), name, pos);
            registry.refreshLabels(server, name);
            if (placer instanceof Player player) {
                Runway runway = registry.find(server.dimension(), name);
                player.displayClientMessage(runway != null
                        ? Component.translatable("msg.ivautopilot.runway_complete", name, (int) runway.length())
                        : Component.translatable("msg.ivautopilot.runway_half", name), false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof RunwayMarkerBlockEntity marker && !marker.getRunwayName().isEmpty()) {
            String name = marker.getRunwayName();
            RunwayRegistry registry = RunwayRegistry.get(server);
            registry.removeMarker(server.dimension(), name, pos);
            ivautopilot.taxi.TaxiNetwork.get(server).removeMarker(server.dimension(), pos);
            ivautopilot.taxi.TaxiSync.broadcast(server);
            super.onRemove(state, level, pos, newState, moving);
            registry.refreshLabels(server, name);
            return;
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof ivautopilot.taxi.TaxiWandItem) {
            return InteractionResult.PASS; // let the Taxiway Wand link this marker
        }
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof RunwayMarkerBlockEntity marker) {
            String name = marker.getRunwayName();
            if (!name.isEmpty()) {
                RunwayRegistry.get(server).refreshLabels(server, name); // e.g. markers placed before labels existed
            }
            Runway runway = name.isEmpty() ? null : RunwayRegistry.get(server).find(server.dimension(), name);
            player.displayClientMessage(name.isEmpty() ? Component.translatable("msg.ivautopilot.runway_unnamed")
                    : runway != null ? Component.translatable("msg.ivautopilot.runway_complete", name, (int) runway.length())
                    : Component.translatable("msg.ivautopilot.runway_half", name), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
