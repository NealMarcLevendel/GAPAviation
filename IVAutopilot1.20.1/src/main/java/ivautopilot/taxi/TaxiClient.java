package ivautopilot.taxi;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ivautopilot.IvAutopilot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Client: the taxiway network of the current dimension, drawn on the ground while holding the
 * Taxiway Wand - yellow taxiway links, cyan runway links, the selected waypoint highlighted.
 */
@Mod.EventBusSubscriber(modid = IvAutopilot.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TaxiClient {
    private static volatile TaxiSync.Packet network = new TaxiSync.Packet(java.util.Map.of(), java.util.List.of(), java.util.List.of());

    private TaxiClient() {
    }

    public static void onNetwork(TaxiSync.Packet packet) {
        network = packet;
    }

    public static TaxiSync.Packet network() {
        return network;
    }

    static boolean holdingWand(Player player) {
        return player.getMainHandItem().getItem() instanceof TaxiWandItem || player.getOffhandItem().getItem() instanceof TaxiWandItem;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.player == null || !holdingWand(mc.player)) {
            return;
        }
        TaxiSync.Packet net = network;
        ItemStack wand = mc.player.getMainHandItem().getItem() instanceof TaxiWandItem ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
        BlockPos selected = TaxiWandItem.selected(wand);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        for (BlockPos[] l : net.links()) {
            line(lines, m, n, center(l[0], 0.15), center(l[1], 0.15), 1f, 0.85f, 0.2f);
        }
        for (BlockPos[] l : net.runwayLinks()) {
            line(lines, m, n, center(l[0], 0.15), center(l[1], 1.05), 0.3f, 0.9f, 1f);
        }
        for (BlockPos node : net.nodes().keySet()) {
            boolean sel = node.equals(selected);
            float r = sel ? 0.3f : 1f, g = 1f, b = sel ? 0.3f : 1f;
            Vec3 c = center(node, 0.1);
            double s = sel ? 0.6 : 0.35;
            line(lines, m, n, c.add(-s, 0, 0), c.add(s, 0, 0), r, g, b);
            line(lines, m, n, c.add(0, 0, -s), c.add(0, 0, s), r, g, b);
            line(lines, m, n, c, c.add(0, sel ? 2.5 : 1.2, 0), r, g, b);
        }
        pose.popPose();
        RenderSystem.lineWidth(3f);
        buffers.endBatch(RenderType.lines());
        RenderSystem.lineWidth(1f);
    }

    private static Vec3 center(BlockPos p, double dy) {
        return new Vec3(p.getX() + 0.5, p.getY() + dy, p.getZ() + 0.5);
    }

    private static void line(VertexConsumer vc, Matrix4f m, Matrix3f n, Vec3 a, Vec3 b, float r, float g, float bl) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1.0E-6) {
            return;
        }
        float nx = (float) (d.x / len), ny = (float) (d.y / len), nz = (float) (d.z / len);
        vc.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, 1f).normal(n, nx, ny, nz).endVertex();
        vc.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, 1f).normal(n, nx, ny, nz).endVertex();
    }
}
