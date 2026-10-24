package ivautopilot.taxi;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A point on a taxiway. Flat (1/16 block) and without collision, so wheels roll straight over it.
 * Link waypoints into taxiways with the Taxiway Wand; link a waypoint to a Runway Marker to make it
 * that runway end's entrance/exit. Rename one in an anvil (e.g. "Gate A2") to make it a gate/stand
 * that planes can be sent to.
 */
public class TaxiWaypointBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);

    public TaxiWaypointBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @SuppressWarnings("deprecation")
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TaxiWaypointBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof TaxiWaypointBlockEntity waypoint) {
            String name = stack.hasCustomHoverName() ? stack.getHoverName().getString().trim() : "";
            waypoint.setWaypointName(name);
            TaxiNetwork.get(server).addNode(server.dimension(), pos, name);
            int links = WaypointGenerator.autoLink(server, pos, false);
            TaxiSync.broadcast(server);
            if (placer instanceof Player player) {
                player.displayClientMessage(name.isEmpty() ? Component.translatable("msg.ivautopilot.taxi_placed_auto", links)
                        : Component.translatable("msg.ivautopilot.gate_placed", name), true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            TaxiNetwork.get(server).removeNode(server.dimension(), pos);
            TaxiSync.broadcast(server);
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof TaxiWandItem) {
            return InteractionResult.PASS; // the wand handles it
        }
        if (level instanceof ServerLevel server) {
            TaxiNetwork.Graph g = TaxiNetwork.get(server).graph(server.dimension());
            String name = g.nodes.getOrDefault(pos, "");
            player.displayClientMessage(Component.translatable("msg.ivautopilot.taxi_info", name.isEmpty() ? "Taxi waypoint" : name,
                    g.neighbours(pos).size(), g.runwayLinks.getOrDefault(pos, java.util.Set.of()).size()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
