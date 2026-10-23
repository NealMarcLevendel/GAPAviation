package ivautopilot.track;

import java.util.List;

/** Client side: the latest aircraft list from the server, for whichever map mod displays it. */
public final class TrackerClient {
    private static volatile List<TrackedAircraft> latest = List.of();
    private static volatile Runnable listener;

    private TrackerClient() {
    }

    public static void update(List<TrackedAircraft> aircraft) {
        latest = List.copyOf(aircraft);
        Runnable l = listener;
        if (l != null) {
            l.run();
        }
    }

    public static List<TrackedAircraft> latest() {
        return latest;
    }

    /** A map integration registers itself here; called on the client thread whenever a new list arrives. */
    public static void setListener(Runnable runnable) {
        listener = runnable;
    }
}
