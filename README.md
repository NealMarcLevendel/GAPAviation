# GAP Aviation (Forge 1.20.1)

Three mods for Immersive Vehicles, developed and tested together:

| Folder | Mod |
|---|---|
| `GoldenAeroTransport1.20.1/` | Golden Aerotransport Partnership aircraft pack, ported to 1.20.1 |
| `GAPCabinWalk1.20.1/` | Walk around inside aircraft in flight, interior sound, head inertia |
| `IVAutopilot1.20.1/` | Autopilot: pitch/altitude hold, runway-to-runway takeoff, navigation and landing |
| `instance/` | The shared Minecraft game folder: worlds, settings, logs, `mods/` |

All commands, keys and controls: see `COMMANDS.txt`.

## Run
```
./run.sh            # Minecraft with all three mods
./run.sh --dh       # ...plus Distant Horizons
./run.sh build      # release jars of all three into dist/
```
Java 17 is selected automatically; your default Java isn't changed. The three project folders must
stay side by side in this folder (they build each other by relative path). The whole `GAPAviation`
folder can be moved or renamed freely.

Each project can still be opened and run on its own (`./gradlew runClient` inside it); the Golden
Aerotransport and Cabin Walk projects then use their own `run/` folders, IV Autopilot uses `instance/`.
