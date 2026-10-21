package ivautopilot.nav;

import ivautopilot.ApConfig;
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
 * Defines an airport's no-bank zone: within the radius around this block (and up to the configured
 * ceiling above it) the autopilot keeps the wings level and turns with rudder only. Optionally
 * rename it in an anvil. Right-click: radius +50; sneak + right-click: radius -50.
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
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof AirportMarkerBlockEntity marker) {
            String name = stack.hasCustomHoverName() ? stack.getHoverName().getString().trim() : "";
            marker.set(name, AirportMarkerBlockEntity.DEFAULT_RADIUS);
            AirportRegistry.get(server).put(server.dimension(), marker.airport());
            if (placer instanceof Player player) {
                describe(player, marker, false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            AirportRegistry.get(server).remove(server.dimension(), pos);
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
            int step = player.isShiftKeyDown() ? -AirportMarkerBlockEntity.STEP : AirportMarkerBlockEntity.STEP;
            marker.set(marker.getAirportName(), marker.getRadius() + step);
            AirportRegistry.get(server).put(server.dimension(), marker.airport());
            describe(player, marker, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void describe(Player player, AirportMarkerBlockEntity marker, boolean actionBar) {
        player.displayClientMessage(Component.translatable("msg.ivautopilot.airport_zone", marker.airport().name(),
                marker.getRadius(), ApConfig.AIRPORT_CEILING.get().intValue()), actionBar);
    }
}
