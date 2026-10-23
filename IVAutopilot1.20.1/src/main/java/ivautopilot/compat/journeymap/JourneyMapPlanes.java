package ivautopilot.compat.journeymap;

import ivautopilot.IvAutopilot;
import ivautopilot.track.TrackedAircraft;
import ivautopilot.track.TrackerClient;
import ivautopilot.track.TrackerClientConfig;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Plane tracker on JourneyMap's full-screen map and minimap: a tinted, rotated plane icon for every
 * aircraft in the world (labelled with name, altitude, speed and ETA), its planned autopilot path as
 * a line to the destination, and the destination runway. Loaded by JourneyMap only when it's
 * installed (this is the only class that touches its API).
 *
 * Colours: green = autopilot flying to a runway, yellow = pitch/altitude hold, white = flown by hand,
 * grey = parked or last seen (not loaded).
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class JourneyMapPlanes implements IClientPlugin {
    private static final ResourceLocation ICON = new ResourceLocation(IvAutopilot.MODID, "textures/tracker/plane.png");
    private static final int GREEN = 0x5CFF6A, YELLOW = 0xFFD84A, WHITE = 0xFFFFFF, GREY = 0x9A9A9A, RUNWAY = 0xFFFFFF;

    private IClientAPI api;

    @Override
    public void initialize(IClientAPI jmClientApi) {
        this.api = jmClientApi;
        TrackerClient.setListener(this::refresh);
        IvAutopilot.LOGGER.info("Plane tracker: showing aircraft on JourneyMap");
    }

    @Override
    public String getModId() {
        return IvAutopilot.MODID;
    }

    /** Called on the client thread whenever the server sends a new aircraft list (once a second). */
    private void refresh() {
        if (api == null) {
            return;
        }
        api.removeAll(IvAutopilot.MODID);
        for (TrackedAircraft a : TrackerClient.latest()) {
            try {
                show(a);
            } catch (Exception e) {
                IvAutopilot.LOGGER.debug("Plane tracker: couldn't show {}", a.name(), e);
            }
        }
    }

    private void show(TrackedAircraft a) throws Exception {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(a.dimension()));
        int color = switch (a.status()) {
            case "AUTOPILOT" -> GREEN;
            case "AP PITCH", "AP ALT" -> YELLOW;
            case "MANUAL" -> WHITE;
            default -> GREY;
        };
        if (!a.live()) {
            color = GREY;
        }

        // Planned path, then the destination runway, under the plane icon.
        if (a.path().length >= 4) {
            api.show(line(dimension, a.path(), color, 3.5f, 0.9f).setTitle(a.name() + " to " + a.destination()).setDisplayOrder(10));
        }
        if (a.runway().length == 4) {
            api.show(line(dimension, a.runway(), RUNWAY, 8f, 1f).setTitle("Runway " + a.destination()).setDisplayOrder(11));
        }

        int size = TrackerClientConfig.ICON_SIZE.get();
        float textScale = TrackerClientConfig.TEXT_SCALE.get().floatValue();
        MapImage icon = new MapImage(ICON, 0, 0, 64, 64, color, 1f); // 64 px art, shown at the configured size
        icon.setDisplayWidth(size).setDisplayHeight(size);
        icon.centerAnchors();
        icon.setRotation(Math.round(a.bearing()));
        MarkerOverlay marker = new MarkerOverlay(IvAutopilot.MODID, BlockPos.containing(a.x(), a.y(), a.z()), icon);
        marker.setDimension(dimension);
        if (TrackerClientConfig.SHOW_LABELS.get()) {
            marker.setLabel(label(a));
        }
        marker.setTitle(title(a));
        marker.setTextProperties(new TextProperties().setColor(color).setBackgroundOpacity(0.55f).setScale(textScale)
                .setFontShadow(true).setOffsetY(Math.round(size * 0.6f + 4)));
        marker.setDisplayOrder(20);
        api.show(marker);
    }

    private static String label(TrackedAircraft a) {
        if (!a.live()) {
            return a.name() + " (last seen)";
        }
        StringBuilder s = new StringBuilder(a.name()).append("  ").append(Math.round(a.y())).append("  ").append(Math.round(a.speedKmh())).append(" km/h");
        if (!a.destination().isEmpty()) {
            s.append("  > ").append(a.destination());
            if (a.etaSeconds() >= 0) {
                int t = Math.round(a.etaSeconds());
                s.append(String.format("  %d:%02d", t / 60, t % 60));
            }
        }
        return s.toString();
    }

    private static String title(TrackedAircraft a) {
        String where = Math.round(a.x()) + " " + Math.round(a.y()) + " " + Math.round(a.z());
        if (!a.live()) {
            return a.name() + " - last seen at " + where;
        }
        String s = a.name() + " - " + a.status() + (a.phase().isEmpty() ? "" : " (" + a.phase().toLowerCase().replace('_', ' ') + ")")
                + " - " + where + " - heading " + Math.round(a.bearing());
        return a.destination().isEmpty() ? s : s + " - to " + a.destination();
    }

    /**
     * JourneyMap has no polyline overlay, so draw a line as a polygon that goes out along the points
     * and back the same way, stroked and not filled.
     */
    private static PolygonOverlay line(ResourceKey<Level> dimension, float[] xz, int color, float width, float opacity) {
        List<BlockPos> points = new ArrayList<>();
        int n = xz.length / 2;
        for (int i = 0; i < n; i++) {
            points.add(BlockPos.containing(xz[2 * i], 64, xz[2 * i + 1]));
        }
        for (int i = n - 2; i >= 1; i--) {
            points.add(points.get(i));
        }
        if (points.size() < 3) {
            points.add(points.get(0));
        }
        ShapeProperties shape = new ShapeProperties().setStrokeColor(color).setStrokeOpacity(opacity).setStrokeWidth(width).setFillOpacity(0f);
        return new PolygonOverlay(IvAutopilot.MODID, dimension, shape, new MapPolygon(points));
    }
}
