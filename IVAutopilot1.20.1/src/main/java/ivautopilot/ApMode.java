package ivautopilot;

public enum ApMode {
    OFF, PITCH, ALTITUDE, NAV;

    /** The G key cycles the manual modes; NAV is started with /autopilot fly. */
    public ApMode next() {
        return switch (this) {
            case OFF -> PITCH;
            case PITCH -> ALTITUDE;
            case ALTITUDE, NAV -> OFF;
        };
    }
}
