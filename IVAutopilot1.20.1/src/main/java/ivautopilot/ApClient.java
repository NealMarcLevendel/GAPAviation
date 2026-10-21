package ivautopilot;

import com.mojang.blaze3d.platform.InputConstants;
import mcinterface1201.BuilderEntityLinkedSeat;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartSeat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/** Keys (pilot seat only) and a small HUD readout. */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ApClient {
    private static final String CATEGORY = "key.categories.ivautopilot";
    public static final KeyMapping CYCLE_MODE = key("cycle_mode", GLFW.GLFW_KEY_G);
    public static final KeyMapping AUTO_TRIM = key("auto_trim", GLFW.GLFW_KEY_O);
    public static final KeyMapping TARGET_UP = key("target_up", GLFW.GLFW_KEY_UP);
    public static final KeyMapping TARGET_DOWN = key("target_down", GLFW.GLFW_KEY_DOWN);

    @Nullable
    private static ApNetwork.Status status;
    /** Client ticks since the last status (the server sends one every half second to everyone aboard). */
    private static int statusAge = Integer.MAX_VALUE;

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.ivautopilot." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    static void onStatus(ApNetwork.Status msg) {
        status = msg;
        statusAge = 0;
    }

    /** The aircraft whose autopilot status we're receiving: aboard but not in a seat (walking the cabin). */
    @Nullable
    private static EntityVehicleF_Physics statusAircraft() {
        Minecraft mc = Minecraft.getInstance();
        if (status == null || statusAge > 30 || mc.level == null) {
            return null;
        }
        minecrafttransportsimulator.entities.components.AEntityA_Base entity =
                mcinterface1201.WrapperWorld.getWrapperFor(mc.level).getEntity(status.aircraft());
        return entity instanceof EntityVehicleF_Physics vehicle ? vehicle : null;
    }

    @Mod.EventBusSubscriber(modid = IvAutopilot.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            event.register(CYCLE_MODE);
            event.register(AUTO_TRIM);
            event.register(TARGET_UP);
            event.register(TARGET_DOWN);
        }

        @SubscribeEvent
        public static void onRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(ApRegistry.RUNWAY_MARKER_BE.get(), context -> new MarkerLabelRenderer<>());
            event.registerBlockEntityRenderer(ApRegistry.AIRPORT_MARKER_BE.get(), context -> new MarkerLabelRenderer<>());
        }

        @SubscribeEvent
        public static void onOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("autopilot", (gui, graphics, partialTick, width, height) -> renderHud(graphics));
        }
    }

    @Nullable
    private static EntityVehicleF_Physics riddenAircraft() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() instanceof BuilderEntityLinkedSeat seatBuilder
                && seatBuilder.entity instanceof PartSeat seat && seat.vehicleOn != null
                && seat.vehicleOn.definition.motorized != null && seat.vehicleOn.definition.motorized.isAircraft) {
            return seat.vehicleOn;
        }
        return null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().player == null) {
            return;
        }
        if (statusAge < Integer.MAX_VALUE) {
            statusAge++;
        }
        boolean inAircraft = riddenAircraft() != null || statusAircraft() != null;
        send(CYCLE_MODE, ApNetwork.Action.CYCLE_MODE, inAircraft);
        send(AUTO_TRIM, ApNetwork.Action.TOGGLE_AUTO_TRIM, inAircraft);
        send(TARGET_UP, ApNetwork.Action.TARGET_UP, inAircraft);
        send(TARGET_DOWN, ApNetwork.Action.TARGET_DOWN, inAircraft);
    }

    private static void send(KeyMapping key, ApNetwork.Action action, boolean inAircraft) {
        while (key.consumeClick()) {
            if (inAircraft) {
                ApNetwork.CHANNEL.sendToServer(new ApNetwork.Command(action));
            }
        }
    }

    private static void renderHud(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        EntityVehicleF_Physics aircraft = riddenAircraft();
        if (aircraft == null) {
            aircraft = statusAircraft();
        }
        if (aircraft == null || mc.options.hideGui) {
            return;
        }
        ApNetwork.Status s = status != null && status.aircraft().equals(aircraft.uniqueUUID) ? status : null;
        ApMode mode = s != null ? ApMode.values()[s.mode()] : ApMode.OFF;
        boolean autoTrim = s != null && s.autoTrim();
        if (mode == ApMode.OFF && !autoTrim) {
            return; // only show when something is engaged
        }
        String line = switch (mode) {
            case OFF -> "AP OFF";
            case PITCH -> String.format("AP PITCH %+.0f\u00b0", s.targetPitch());
            case ALTITUDE -> String.format("AP ALT %.0f", s.targetAltitude());
            case NAV -> String.format("AP NAV  ALT %.0f  SPD %.0f km/h", s.targetAltitude(), s.targetSpeed() * 3.6);
        };
        String trim = String.format("TRIM %+.1f\u00b0  PITCH %+.1f\u00b0  BANK %+.0f\u00b0  ALT %.0f  SPD %.0f km/h",
                aircraft.elevatorTrimVar.currentValue, ApServer.pitchUp(aircraft), ApServer.bankOf(aircraft),
                aircraft.position.y, aircraft.indicatedSpeed * 3.6);
        int x = 6, y = 6;
        graphics.drawString(mc.font, Component.literal(line + (autoTrim ? "  AUTO-TRIM" : "")), x, y, 0x7CFC7C, true);
        graphics.drawString(mc.font, Component.literal(trim), x, y + 10, 0xD0D0D0, true);
        int row = y + 20;
        if (s != null && !s.runway().isEmpty()) {
            graphics.drawString(mc.font, Component.literal(s.runway()), x, row, 0xFFE070, true);
            row += 10;
        }
        if (s != null && !s.info().isEmpty()) {
            graphics.drawString(mc.font, Component.literal(s.info()), x, row, 0x9AD0FF, true);
        }
    }

    private ApClient() {
    }
}
