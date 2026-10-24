package ivautopilot.taxi;

import ivautopilot.nav.RunwayMarkerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Taxiway Wand: right-click a Taxi Waypoint, then another one to link them (or unlink if already
 * linked) - or then a Runway Marker to make the first waypoint that runway end's entrance/exit.
 * Sneak + right-click (in the air) clears the selection. Holding it shows the network on the ground.
 */
public class TaxiWandItem extends Item {
    private static final String SELECTED = "selected";

    public TaxiWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!(level instanceof ServerLevel server) || player == null) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        BlockPos pos = context.getClickedPos();
        Block block = level.getBlockState(pos).getBlock();
        ItemStack stack = context.getItemInHand();
        BlockPos selected = stack.hasTag() && stack.getTag().contains(SELECTED) ? NbtUtils.readBlockPos(stack.getTag().getCompound(SELECTED)) : null;
        TaxiNetwork network = TaxiNetwork.get(server);

        if (block instanceof TaxiWaypointBlock) {
            if (selected == null || selected.equals(pos) || !(level.getBlockState(selected).getBlock() instanceof TaxiWaypointBlock)) {
                stack.getOrCreateTag().put(SELECTED, NbtUtils.writeBlockPos(pos));
                player.displayClientMessage(Component.translatable("msg.ivautopilot.wand_selected"), true);
            } else {
                boolean linked = network.toggleLink(server.dimension(), selected, pos);
                stack.getOrCreateTag().put(SELECTED, NbtUtils.writeBlockPos(pos)); // chain: next click links from here
                TaxiSync.broadcast(server);
                player.displayClientMessage(Component.translatable(linked ? "msg.ivautopilot.wand_linked" : "msg.ivautopilot.wand_unlinked",
                        Math.round(Math.sqrt(selected.distSqr(pos)))), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (block instanceof RunwayMarkerBlock) {
            if (selected == null || !(level.getBlockState(selected).getBlock() instanceof TaxiWaypointBlock)) {
                player.displayClientMessage(Component.translatable("msg.ivautopilot.wand_waypoint_first"), true);
            } else {
                boolean linked = network.toggleRunwayLink(server.dimension(), selected, pos);
                TaxiSync.broadcast(server);
                player.displayClientMessage(Component.translatable(linked ? "msg.ivautopilot.wand_runway_linked" : "msg.ivautopilot.wand_runway_unlinked"), true);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.hasTag()) {
            stack.getTag().remove(SELECTED);
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("msg.ivautopilot.wand_cleared"), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    /** The waypoint currently selected on this wand, if any (client uses it to highlight). */
    public static BlockPos selected(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(SELECTED) ? NbtUtils.readBlockPos(stack.getTag().getCompound(SELECTED)) : null;
    }
}
