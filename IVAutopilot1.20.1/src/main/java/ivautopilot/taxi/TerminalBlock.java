package ivautopilot.taxi;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Terminal Gate: where planes park. A taxi waypoint that is always a gate - named after the item if
 * it was renamed in an anvil, otherwise "Gate 1", "Gate 2", ... - and links itself to the nearest
 * taxi waypoint.
 */
public class TerminalBlock extends TaxiWaypointBlock {
    public TerminalBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof TaxiWaypointBlockEntity waypoint) {
            TaxiNetwork network = TaxiNetwork.get(server);
            String name = stack.hasCustomHoverName() ? stack.getHoverName().getString().trim() : "";
            if (name.isEmpty() || network.graph(server.dimension()).gate(name) != null) {
                name = WaypointGenerator.nextGateName(network.graph(server.dimension()));
            }
            waypoint.setWaypointName(name);
            network.addNode(server.dimension(), pos, name);
            int links = WaypointGenerator.autoLink(server, pos, true);
            TaxiSync.broadcast(server);
            if (placer instanceof Player player) {
                player.displayClientMessage(Component.translatable(links > 0 ? "msg.ivautopilot.terminal_placed" : "msg.ivautopilot.terminal_placed_unlinked", name), true);
            }
        }
    }
}
