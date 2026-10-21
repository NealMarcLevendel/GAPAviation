package gapcabinwalk.feel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import gapcabinwalk.GapCabinWalk;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client command (works on any server, no permissions needed):
 *   /headinertia                      show the current settings
 *   /headinertia <intensity>          set both the turning and the acceleration intensity (0-5, 1 = default)
 *   /headinertia turning <value>      lag when the vehicle rolls / pitches / yaws
 *   /headinertia acceleration <value> nod with braking, speeding up and bumps
 *   /headinertia roll <value>         extra lag when rolling into / out of a bank (0-10, default 2)
 *   /headinertia tilt on | off        bank your view with the cabin while walking around
 *   /headinertia on | off
 * Settings are saved to config/gapcabinwalk-client.toml.
 */
@Mod.EventBusSubscriber(modid = GapCabinWalk.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeadInertiaCommand {
    private static final double MAX = 5;

    private HeadInertiaCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("headinertia")
                .executes(HeadInertiaCommand::show)
                .then(Commands.literal("on").executes(ctx -> enable(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> enable(ctx, false)))
                .then(Commands.literal("turning").then(Commands.argument("value", DoubleArgumentType.doubleArg(0, MAX))
                        .executes(ctx -> set(ctx, HeadInertiaConfig.STRENGTH, null))))
                .then(Commands.literal("roll").then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 10))
                        .executes(ctx -> set(ctx, HeadInertiaConfig.ROLL_STRENGTH, null))))
                .then(Commands.literal("tilt")
                        .then(Commands.literal("on").executes(ctx -> tilt(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> tilt(ctx, false))))
                .then(Commands.literal("acceleration").then(Commands.argument("value", DoubleArgumentType.doubleArg(0, MAX))
                        .executes(ctx -> set(ctx, HeadInertiaConfig.ACCELERATION_STRENGTH, null))))
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0, MAX))
                        .executes(ctx -> set(ctx, HeadInertiaConfig.STRENGTH, HeadInertiaConfig.ACCELERATION_STRENGTH))));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, ForgeConfigSpec.DoubleValue first, ForgeConfigSpec.DoubleValue second) {
        double value = DoubleArgumentType.getDouble(ctx, "value");
        first.set(value);
        if (second != null) {
            second.set(value);
        }
        if (value > 0 && !HeadInertiaConfig.ENABLED.get()) {
            HeadInertiaConfig.ENABLED.set(true); // setting an intensity implies you want it on
        }
        HeadInertiaConfig.SPEC.save();
        return show(ctx);
    }

    private static int tilt(CommandContext<CommandSourceStack> ctx, boolean on) {
        HeadInertiaConfig.TILT_WHEN_STANDING.set(on);
        HeadInertiaConfig.SPEC.save();
        return show(ctx);
    }

    private static int enable(CommandContext<CommandSourceStack> ctx, boolean on) {
        HeadInertiaConfig.ENABLED.set(on);
        HeadInertiaConfig.SPEC.save();
        return show(ctx);
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable("msg.gapcabinwalk.head_inertia_status",
                Component.translatable(HeadInertiaConfig.ENABLED.get() ? "msg.gapcabinwalk.on" : "msg.gapcabinwalk.off"),
                String.format("%.2f", HeadInertiaConfig.STRENGTH.get()),
                String.format("%.2f", HeadInertiaConfig.ACCELERATION_STRENGTH.get()),
                String.format("%.2f", HeadInertiaConfig.ROLL_STRENGTH.get()),
                Component.translatable(HeadInertiaConfig.TILT_WHEN_STANDING.get() ? "msg.gapcabinwalk.on" : "msg.gapcabinwalk.off")), false);
        return 1;
    }
}
