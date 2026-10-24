package ivautopilot;

import com.mojang.blaze3d.vertex.PoseStack;
import ivautopilot.nav.AirportMarkerBlockEntity;
import ivautopilot.nav.RunwayMarkerBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating name tag over Runway and Airport Markers: name, coordinates and runway heading/length
 * (or airport zone radius). Grows with distance so it can be read from the air, and shows through
 * terrain like a player name tag.
 */
public final class MarkerLabelRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
    private static final int FULL_BRIGHT = 0xF000F0;

    @Override
    public void render(T entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        List<Component> lines = lines(entity);
        if (lines.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        BlockPos pos = entity.getBlockPos();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double distance = camera.distanceTo(Vec3.atCenterOf(pos));
        float scale = 0.025F * (float) Math.max(1, distance / 24);

        poseStack.pushPose();
        poseStack.translate(0.5, 1.3 + lines.size() * 10 * scale, 0.5);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-scale, -scale, scale);
        Matrix4f matrix = poseStack.last().pose();
        int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            float x = -font.width(line) / 2F;
            float y = i * 10;
            font.drawInBatch(line, x, y, 0x40FFFFFF, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, background, FULL_BRIGHT);
            font.drawInBatch(line, x, y, 0xFFFFFFFF, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
        poseStack.popPose();
    }

    private static List<Component> lines(BlockEntity entity) {
        List<Component> lines = new ArrayList<>();
        BlockPos p = entity.getBlockPos();
        Component coords = Component.literal(p.getX() + " " + p.getY() + " " + p.getZ()).withStyle(ChatFormatting.GRAY);
        if (entity instanceof RunwayMarkerBlockEntity runway) {
            String name = runway.getRunwayName();
            lines.add(Component.literal(name.isEmpty() ? "Unnamed runway marker" : "RWY " + name).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            if (!runway.getInfo().isEmpty()) {
                lines.add(Component.literal(runway.getInfo()));
            }
            lines.add(coords);
        } else if (entity instanceof ivautopilot.taxi.TaxiWaypointBlockEntity waypoint) {
            // Gates (named waypoints) always; plain waypoints only while holding the wand.
            boolean wand = net.minecraft.client.Minecraft.getInstance().player != null
                    && (net.minecraft.client.Minecraft.getInstance().player.getMainHandItem().getItem() instanceof ivautopilot.taxi.TaxiWandItem
                    || net.minecraft.client.Minecraft.getInstance().player.getOffhandItem().getItem() instanceof ivautopilot.taxi.TaxiWandItem);
            if (!waypoint.getWaypointName().isEmpty()) {
                lines.add(Component.literal(waypoint.getWaypointName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                if (wand) {
                    lines.add(coords);
                }
            } else if (wand) {
                lines.add(Component.literal("Taxi waypoint").withStyle(ChatFormatting.YELLOW));
            }
        } else if (entity instanceof AirportMarkerBlockEntity airport) {
            lines.add(Component.literal(airport.getAirportName().isEmpty() ? "Airport" : airport.getAirportName()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            if (!airport.getInfo().isEmpty()) {
                lines.add(Component.literal(airport.getInfo()));
            }
            lines.add(coords);
        }
        return lines;
    }

    @Override
    public boolean shouldRenderOffScreen(T entity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 512;
    }
}
