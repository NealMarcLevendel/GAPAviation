package ivautopilot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * /autopilot fly "<runway>" [cruise altitude] [cruise speed km/h] [delay <seconds>]
 * /autopilot taxi "<terminal gate | runway>" [delay <seconds>]
 * /autopilot off
 * /autopilot runways
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ApCommands {
    private static final int MAX_DELAY = 24 * 3600;

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("autopilot")
                .then(Commands.literal("fly")
                        .then(Commands.argument("runway", StringArgumentType.string())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(quoted(runwayNames(ctx)), builder))
                                .executes(ctx -> fly(ctx, null, null, 0))
                                .then(delay(ctx -> fly(ctx, null, null, seconds(ctx))))
                                .then(Commands.argument("altitude", DoubleArgumentType.doubleArg(-64, 2000))
                                        .executes(ctx -> fly(ctx, DoubleArgumentType.getDouble(ctx, "altitude"), null, 0))
                                        .then(delay(ctx -> fly(ctx, DoubleArgumentType.getDouble(ctx, "altitude"), null, seconds(ctx))))
                                        .then(Commands.argument("speedKmh", DoubleArgumentType.doubleArg(30, 1500))
                                                .executes(ctx -> fly(ctx, DoubleArgumentType.getDouble(ctx, "altitude"),
                                                        DoubleArgumentType.getDouble(ctx, "speedKmh") / 3.6, 0))
                                                .then(delay(ctx -> fly(ctx, DoubleArgumentType.getDouble(ctx, "altitude"),
                                                        DoubleArgumentType.getDouble(ctx, "speedKmh") / 3.6, seconds(ctx))))))))
                .then(Commands.literal("off").executes(ctx -> {
                    ApServer.stop(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("runways").executes(ApCommands::list))
                .then(Commands.literal("taxi")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    java.util.List<String> names = new java.util.ArrayList<>(gateNames(ctx));
                                    names.addAll(runwayNames(ctx));
                                    return SharedSuggestionProvider.suggest(quoted(names), builder);
                                })
                                .executes(ctx -> taxi(ctx, 0))
                                .then(delay(ctx -> taxi(ctx, seconds(ctx)))))));
        var gen = event.getDispatcher().register(Commands.literal("genwaypoints").requires(src -> src.hasPermission(2))
                .then(Commands.literal("clear")
                        .then(Commands.literal("airport").executes(ctx -> clearWaypoints(ctx, true)))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 2048)).executes(ctx -> clearWaypoints(ctx, false))))
                .then(Commands.literal("entrances").executes(ctx -> {
                    int n = ivautopilot.taxi.WaypointGenerator.linkEntrances(ctx.getSource().getLevel(),
                            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.gen_entrances", n), true);
                    return n;
                }))
                .then(Commands.literal("cancel").executes(ctx -> {
                    ivautopilot.taxi.WaypointGenerator.cancel();
                    ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.gen_cancelled"), true);
                    return 1;
                }))
                .then(Commands.literal("airport")
                        .executes(ctx -> generateWaypoints(ctx, true))
                        .then(Commands.argument("blocks", StringArgumentType.greedyString())
                                .suggests(ApCommands::suggestSurface)
                                .executes(ctx -> generateWaypoints(ctx, true))))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 2048))
                        .executes(ctx -> generateWaypoints(ctx, false))
                        .then(Commands.argument("blocks", StringArgumentType.greedyString())
                                .suggests(ApCommands::suggestSurface)
                                .executes(ctx -> generateWaypoints(ctx, false)))));
        event.getDispatcher().register(Commands.literal("genWayPoints").requires(src -> src.hasPermission(2)).redirect(gen));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestSurface(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        var src = ctx.getSource();
        var under = src.getLevel().getBlockState(net.minecraft.core.BlockPos.containing(src.getPosition()).below());
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(under.getBlock());
        String typed = builder.getRemaining();
        String prefix = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
        return SharedSuggestionProvider.suggest(id == null ? java.util.List.of() : java.util.List.of(prefix + id), builder);
    }

    /** The Airport Marker zone the command source is standing in, or null. */
    @javax.annotation.Nullable
    private static ivautopilot.nav.Airport airportHere(CommandContext<CommandSourceStack> ctx) {
        var pos = ctx.getSource().getPosition();
        return ivautopilot.nav.AirportRegistry.get(ctx.getSource().getLevel()).containing(ctx.getSource().getLevel().dimension(),
                pos.x, pos.y, pos.z, ApConfig.AIRPORT_CEILING.get());
    }

    private static int generateWaypoints(CommandContext<CommandSourceStack> ctx, boolean airport) {
        if (ivautopilot.taxi.WaypointGenerator.busy()) {
            ctx.getSource().sendFailure(Component.translatable("msg.ivautopilot.gen_busy"));
            return 0;
        }
        java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.HashSet<>();
        String list;
        try {
            list = StringArgumentType.getString(ctx, "blocks");
        } catch (IllegalArgumentException noList) {
            list = ""; // no list: any paving (not natural ground) counts
        }
        for (String part : list.split("[,\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(part.contains(":") ? part : "minecraft:" + part);
            var block = id == null ? null : net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                ctx.getSource().sendFailure(Component.translatable("msg.ivautopilot.gen_unknown_block", part));
                return 0;
            }
            blocks.add(block);
        }
        var here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        int minX, minZ, maxX, maxZ;
        int groundY = here.getY() - 1; // the block the player stands on (checked against the surface found)
        if (airport) {
            var zone = airportHere(ctx);
            if (zone == null) {
                ctx.getSource().sendFailure(Component.translatable("msg.ivautopilot.gen_no_airport"));
                return 0;
            }
            int max = ivautopilot.taxi.WaypointGenerator.MAX_SIDE;
            if (zone.sizeX() > max || zone.sizeZ() > max) {
                ctx.getSource().sendFailure(Component.translatable("msg.ivautopilot.gen_too_big", zone.sizeX(), zone.sizeZ(), max));
                return 0;
            }
            minX = zone.minX();
            minZ = zone.minZ();
            maxX = zone.maxX();
            maxZ = zone.maxZ();
            // First guess at the ground level: the corner markers stand on it (checked against the surface found).
            groundY = Math.min(zone.a().getY(), zone.b().getY()) - 1;
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.gen_started_airport", zone.name(), zone.sizeX(), zone.sizeZ()), true);
        } else {
            int radius = IntegerArgumentType.getInteger(ctx, "radius");
            minX = here.getX() - radius;
            minZ = here.getZ() - radius;
            maxX = here.getX() + radius;
            maxZ = here.getZ() + radius;
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.gen_started", radius), true);
        }
        var source = ctx.getSource();
        ivautopilot.taxi.WaypointGenerator.start(source.getLevel(), minX, minZ, maxX, maxZ, groundY, blocks, 24,
                source.getPlayer(), msg -> source.sendSuccess(() -> msg, true));
        return 1;
    }

    private static int clearWaypoints(CommandContext<CommandSourceStack> ctx, boolean airport) {
        var here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        int removed;
        if (airport) {
            var zone = airportHere(ctx);
            if (zone == null) {
                ctx.getSource().sendFailure(Component.translatable("msg.ivautopilot.gen_no_airport"));
                return 0;
            }
            removed = ivautopilot.taxi.WaypointGenerator.clear(ctx.getSource().getLevel(), zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ());
        } else {
            int radius = IntegerArgumentType.getInteger(ctx, "radius");
            removed = ivautopilot.taxi.WaypointGenerator.clear(ctx.getSource().getLevel(), here.getX() - radius, here.getZ() - radius,
                    here.getX() + radius, here.getZ() + radius);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.gen_cleared", removed), true);
        return removed;
    }

    /** "... delay <seconds>" */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> delay(com.mojang.brigadier.Command<CommandSourceStack> command) {
        return Commands.literal("delay").then(Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_DELAY)).executes(command));
    }

    private static int seconds(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "seconds");
    }

    private static java.util.List<String> runwayNames(CommandContext<CommandSourceStack> ctx) {
        var level = ctx.getSource().getLevel();
        return new java.util.ArrayList<>(RunwayRegistry.get(level).names(level.dimension()));
    }

    private static java.util.List<String> gateNames(CommandContext<CommandSourceStack> ctx) {
        var level = ctx.getSource().getLevel();
        java.util.List<String> names = new java.util.ArrayList<>();
        ivautopilot.taxi.TaxiNetwork.get(level).graph(level.dimension()).nodes.values().stream().filter(n -> !n.isEmpty()).forEach(names::add);
        return names;
    }

    private static java.util.stream.Stream<String> quoted(java.util.Collection<String> names) {
        return names.stream().map(n -> n.contains(" ") ? "\"" + n + "\"" : n);
    }

    /** After a successful start: apply the delay (ground only) and say so. */
    private static void applyDelay(CommandContext<CommandSourceStack> ctx, ServerPlayer player, int seconds) {
        if (seconds <= 0) {
            return;
        }
        if (ApServer.setDelay(player, seconds)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.delay_set", seconds), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.delay_ground_only"), false);
        }
    }

    private static int taxi(CommandContext<CommandSourceStack> ctx, int delay) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String target = StringArgumentType.getString(ctx, "target");
        String error = ApServer.startTaxi(player, target);
        if (error != null) {
            ctx.getSource().sendFailure(Component.translatable(error, target));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.taxi_started", target), false);
        applyDelay(ctx, player, delay);
        return 1;
    }

    private static int fly(CommandContext<CommandSourceStack> ctx, Double altitude, Double speed, int delay) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return fly(ctx, StringArgumentType.getString(ctx, "runway"), altitude, speed, delay);
    }

    private static int fly(CommandContext<CommandSourceStack> ctx, String name, Double altitude, Double speed, int delay) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
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
        sendCruise(ctx, player);
        applyDelay(ctx, player, delay);
        return 1;
    }

    private static void sendCruise(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        var flying = ApServer.controlledAircraft(player);
        double cruise = ApServer.cruiseAltitudeOf(flying);
        if (flying != null && !Double.isNaN(cruise)) {
            String kind = ApServer.engineClass(flying).name().toLowerCase(java.util.Locale.ROOT);
            ctx.getSource().sendSuccess(() -> Component.translatable("msg.ivautopilot.cruise", Math.round(cruise), kind,
                    cruise > ApConfig.CLOUD_HEIGHT.get() ? Component.translatable("msg.ivautopilot.above_clouds") : Component.translatable("msg.ivautopilot.below_clouds")), false);
        }
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
