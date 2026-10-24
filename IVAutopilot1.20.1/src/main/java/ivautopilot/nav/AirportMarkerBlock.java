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
import java.util.List;

/**
 * One corner of an airport: place two at opposite corners. The second one placed takes the name of
 * the nearest airport still missing a corner (or name both the same in an anvil); a lone unnamed
 * marker starts "Airport 1", "Airport 2", ... Right-click: show the airport.
 */
public class AirportMarkerBlock extends BaseEntityBlock {
    public AirportMarkerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirportMarkerBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server) {
            AirportRegistry registry = AirportRegistry.get(server);
            String name = stack.hasCustomHoverName() ? stack.getHoverName().getString().trim() : "";
            if (name.isEmpty()) {
                name = registry.nameForNewMarker(server.dimension(), pos);
            }
            registry.put(server.dimension(), pos, name);
            refreshLabels(server, name);
            if (placer instanceof Player player) {
                describe(server, player, name, false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            String name = level.getBlockEntity(pos) instanceof AirportMarkerBlockEntity marker ? marker.getAirportName() : null;
            AirportRegistry.get(server).remove(server.dimension(), pos);
            if (name != null) {
                refreshLabels(server, name);
            }
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof AirportMarkerBlockEntity marker) {
            describe(server, player, marker.getAirportName(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Update the floating labels of this airport's (loaded) corners. */
    private static void refreshLabels(ServerLevel level, String name) {
        List<BlockPos> corners = AirportRegistry.get(level).corners(level.dimension(), name);
        String info = status(corners);
        for (BlockPos c : corners) {
            if (level.isLoaded(c) && level.getBlockEntity(c) instanceof AirportMarkerBlockEntity marker) {
                marker.set(name, info);
            }
        }
    }

    private static String status(List<BlockPos> corners) {
        if (corners.size() == 2) {
            BlockPos a = corners.get(0), b = corners.get(1);
            return "airport " + (Math.abs(a.getX() - b.getX()) + 1) + " x " + (Math.abs(a.getZ() - b.getZ()) + 1);
        }
        return corners.size() < 2 ? "corner 1 of 2: place the opposite corner" : corners.size() + " corners: remove extras (needs exactly 2)";
    }

    private static void describe(ServerLevel level, Player player, String name, boolean actionBar) {
        List<BlockPos> corners = AirportRegistry.get(level).corners(level.dimension(), name);
        player.displayClientMessage(Component.translatable(corners.size() == 2 ? "msg.ivautopilot.airport_zone" : "msg.ivautopilot.airport_corner",
                name, status(corners)), actionBar);
    }
}
