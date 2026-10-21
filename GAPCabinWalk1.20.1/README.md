# GAP Cabin Walk (Forge 1.20.1)

Addon for **Immersive Vehicles** and the **Golden Aerotransport Partnership** pack: get up from your
seat and walk around inside an aircraft, on the ground or in flight.

- **Shift** in a seat: stand up in the aisle next to it.
- Walk, sprint, jump, crouch normally; the aircraft carries you as it moves and turns.
- **Right-click** a seat to sit down again.
- Leave through an **open door** (open it from inside or from the panel).

Requires Immersive Vehicles (MTS) 22.6+ and Golden Aerotransport Partnership 3.3+ for 1.20.1.

## Development
- `./gradlew runClient` builds the sibling `../GoldenAeroTransport1.20.1` project's jar and loads it
  together with this addon (Immersive Vehicles and Distant Horizons are downloaded automatically).
- `python3 tools/gen_cabin_maps.py` regenerates the cabin maps from GAP's models
  (`--pack-resources` / `--pack` for other packs).

## Head inertia
In first person, aboard any Immersive Vehicles vehicle (seated or walking the cabin), your head has
inertia: a quick roll into a bank, a pitch-up or a sudden yaw leaves it lagging behind for a moment
before it catches up (with a little overshoot), braking throws it forward, speeding up pushes it back,
bumps make it nod. A properly banked turn just presses you into your seat. Toggle with **N**
(Controls > GAP Cabin Walk); strength, neck stiffness and damping are in
`config/gapcabinwalk-client.toml`.
Or by command (client-side, works anywhere): `/headinertia 2` doubles both effects,
`/headinertia turning <0-5>` / `/headinertia acceleration <0-5>` set them separately,
`/headinertia roll <0-10>` sets the extra lag when rolling into or out of a bank (default 2),
`/headinertia tilt on|off` banks your view with the cabin while you walk around (otherwise Minecraft
keeps a standing player's horizon level), `/headinertia on|off`, and `/headinertia` shows the current values.
