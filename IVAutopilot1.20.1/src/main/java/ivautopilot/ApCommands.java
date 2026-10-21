package ivautopilot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import ivautopilot.nav.Runway;
import ivautopilot.nav.RunwayRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * /autopilot fly "<runway>" [cruise altitude] [cruise speed km/h]
 * /autopilot off
 * /autopilot runways
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ApCommands {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("autopilot")
                .then(Commands.literal("fly")
                        .then(Commands.argument("runway", StringArgumentType.string())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        RunwayRegistry.get(ctx.getSource().getLevel()).names(ctx.getSource().getLevel().dimension()).stream()
                                                .map(n -> n.contains(" ") ? "\"" + n + "\"" : n), builder))
                                .executes(ctx -> fly(ctx, null, null))
                                .then(Commands.argument("altitude", DoubleArgumentType.doubleArg(-64, 2000))
                                        .executes(ctx -> fly(ctx, DoubleArgumentType.getDouble(ctx, "altitude"), null))
                                        .then(Commands.argument("speedKmh", DoubleArgumentType.doubleArg(30, 1500))
                                                .executes(ctx -> fly(ctx, DoubleArgumentType.getDouble(ctx, "altitude"),
                                                        DoubleArgumentType.getDouble(ctx, "speedKmh") / 3.6))))))
                .then(Commands.literal("off").executes(ctx -> {
                    ApServer.stop(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("runways").executes(ApCommands::list)));
    }

    private static int fly(CommandContext<CommandSourceStack> ctx, Double altitude, Double speed) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "runway");
        Runway runway = RunwayRegistry.get(player.serverLevel()).find(player.level().dimension(), name);
        if (runway == null) {
            ctx.getSource().sendFailure(Component.translatable("msg.ivautopilot.no_runway", name));
            return 0;
        }
        var aircraft = ApServer.controlledAircraft(player);
        boolean fromGround = aircraft != null && ApServer.isOnGround(aircraft);
        String error = ApServer.startNav(player, runway, altitude, speed);
        if (error != null) {
            ctx.getSource().sendFailure(Component.translatable(error));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(fromGround ? "msg.ivautopilot.takeoff_started" : "msg.ivautopilot.nav_started", runway.name()), false);
        var flying = ApServer.controlledAircraft(player);
        double cruise = ApServer.cruiseAltitudeOf(flying);
        if (flying != null && !Double.isNaN(cruise)) {
            String kind = ApServer.engineClass(flying).name().toLowerCase(java.util.Locale.ROOT);
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.cruise", Math.round(cruise), kind,
                    cruise > ApConfig.CLOUD_HEIGHT.get() ? Component.translatable("msg.ivautopilot.above_clouds") : Component.translatable("msg.ivautopilot.below_clouds")), false);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        Map<String, Integer> runways = RunwayRegistry.get(ctx.getSource().getLevel()).list(ctx.getSource().getLevel().dimension());
        if (runways.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.no_runways"), false);
        }
        RunwayRegistry registry = RunwayRegistry.get(ctx.getSource().getLevel());
        var dim = ctx.getSource().getLevel().dimension();
        runways.forEach((name, count) -> {
            net.minecraft.network.chat.MutableComponent line = Component.literal(name).withStyle(net.minecraft.ChatFormatting.YELLOW)
                    .append(Component.literal(count == 2 ? "  " + registry.labelInfo(dim, name, registry.markers(dim, name).get(0))
                            : "  (" + count + " marker" + (count == 1 ? "" : "s") + ", needs exactly 2)").withStyle(net.minecraft.ChatFormatting.WHITE));
            for (net.minecraft.core.BlockPos p : registry.markers(dim, name)) {
                String xyz = p.getX() + " " + p.getY() + " " + p.getZ();
                line.append(Component.literal("  [" + xyz + "]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.AQUA)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, xyz))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy")))));
            }
            ctx.getSource().sendSuccess(() -> line, false);
        });
        return runways.size();
    }

    private ApCommands() {
    }
}
