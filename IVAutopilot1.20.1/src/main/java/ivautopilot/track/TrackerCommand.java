package ivautopilot.track;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import ivautopilot.IvAutopilot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client command for the plane tracker's look on the map:
 *   /planetracker                show the settings
 *   /planetracker size <8-128>   plane icon size, pixels
 *   /planetracker text <0.5-4>   label text size
 *   /planetracker labels on|off  labels under the planes
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrackerCommand {
    private TrackerCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("planetracker")
                .executes(TrackerCommand::show)
                .then(Commands.literal("size").then(Commands.argument("pixels", IntegerArgumentType.integer(8, 128)).executes(ctx -> {
                    TrackerClientConfig.ICON_SIZE.set(IntegerArgumentType.getInteger(ctx, "pixels"));
                    return saved(ctx);
                })))
                .then(Commands.literal("text").then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.5, 4)).executes(ctx -> {
                    TrackerClientConfig.TEXT_SCALE.set(DoubleArgumentType.getDouble(ctx, "scale"));
                    return saved(ctx);
                })))
                .then(Commands.literal("labels")
                        .then(Commands.literal("on").executes(ctx -> {
                            TrackerClientConfig.SHOW_LABELS.set(true);
                            return saved(ctx);
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            TrackerClientConfig.SHOW_LABELS.set(false);
                            return saved(ctx);
                        }))));
    }

    private static int saved(CommandContext<CommandSourceStack> ctx) {
        TrackerClientConfig.SPEC.save();
        TrackerClient.update(TrackerClient.latest()); // redraw now with the new sizes
        return show(ctx);
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("Plane tracker: icon %d px, text %.2f, labels %s",
                TrackerClientConfig.ICON_SIZE.get(), TrackerClientConfig.TEXT_SCALE.get(),
                TrackerClientConfig.SHOW_LABELS.get() ? "on" : "off")), false);
        return 1;
    }
}
