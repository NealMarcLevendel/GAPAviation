# IV Autopilot (Forge 1.20.1)

Pitch hold, altitude hold and auto-trim for **any Immersive Vehicles fixed-wing aircraft**.
The autopilot flies through the aircraft's pitch trim (like a real trim servo), so your own
elevator input still works on top of it.

Keys (pilot seat only, rebindable under Controls > IV Autopilot):
- **G** - cycle: Off -> Pitch hold -> Altitude hold -> Off (engages at your current pitch / altitude)
- **Up / Down arrows** - target pitch +-1 degree, or target altitude +-5 blocks
- **O** - auto-trim on/off: while flying by hand, trim slowly takes over the elevator you keep holding

A small readout appears top-left while anything is engaged.

## Navigation: runway to runway (takeoff, en-route, landing)
1. Rename two **Runway Markers** in an anvil to the same name (e.g. `KJFK 09`) and place one at each
   end of the runway centerline. Each marker shows a floating label (name, heading and length,
   coordinates) readable from the air. `/autopilot runways` lists runways with their marker
   coordinates; click a coordinate to copy it.
2. From the pilot seat: `/autopilot fly "KJFK 09" [cruise altitude] [speed km/h]`, either in the air or
   **lined up on a departure runway** (between its two markers, near the centerline, in the first half,
   facing down it). From the runway the autopilot starts the engines, releases the brakes, sets takeoff
   flaps and full power, steers the centerline, rotates at a rotation speed worked out from the plane's
   weight and wing, climbs out, raises gear and flaps and then continues en-route.
   Touching the flight controls during the takeoff hands it over to you; if rotation speed isn't
   reached by 3/4 of the runway it aborts and brakes. (Joystick users: the stick is read every tick,
   which counts as touching the controls, so take off by hand or unplug the stick.)
   **Cruise altitude** (when you don't give one) grows with the trip: 1 block higher per 30 blocks of
   distance above the higher runway, up to a ceiling by engine type: jets 100 above the clouds
   (y 292), turboprops 40 above (y 232), piston planes 30 below (y 162). The chat says which it picked.
   Trips under ~6 km stay at circuit height. Tune in the `cruise` config section.
3. The autopilot plans a route around high terrain (re-planning every 10 s), banks to follow it,
   holds altitude and speed, and descends toward the runway's approach entry point
   (`nav.approachDistance` before the threshold, `nav.approachHeight` up).
4. **Circuit:** within 4 km of the destination (`nav.patternDistance`) it slows to 1.6 x stall speed with
   takeoff flaps and flies a planned turn - straight - turn path at a firm 30 degree bank
   (`nav.patternBank`) that arrives at the approach entry already lined up with the runway, turning
   outward first if needed. Short hops (e.g. to a runway next door) stay at circuit height instead of
   climbing to cruise. HUD: `CIRCUIT to ...  x km to final`. In NAV the readout ends with `ETA m:ss`, the estimated time to touchdown.
5. **Landing:** gear down and approach flaps; it intercepts the extended centerline, then follows a
   3 degree glide path with full flaps at an approach speed of 1.3 x the plane's own stall speed. At
   5 blocks it idles and flares, then on the ground lets the nose down, uses reverse thrust (if the
   plane has it) and brakes while steering the centerline, and sets the parking brake when stopped.
   An unstable approach below 12 blocks, or not touching down by half the runway, means a
   **go-around**: full power, climb, fly out and try again (3 tries, then it holds at the approach).
   Touching the flight controls during the flare or rollout hands over to you. `/autopilot off`
   (or G) hands back control at any time.
   The rollout is airliner-style: on touchdown spoilers up (GAP animates them from the brake),
   reverse thrust and firm braking (`landing.touchdownBrake`) down to taxi speed
   (`landing.taxiSpeed`, 29 km/h), then spoilers and reversers stowed and a taxi down the runway,
   stopping about 30 blocks before the far end (`landing.rolloutMargin`).
   Planes whose pack defines a speed-based stall alarm (GAP's A220 and 737) are flown at least
   3 blocks/s above that speed (`landing.alarmMargin`), so the alarm only sounds in the flare.

**Leaving the seat:** once engaged, the autopilot keeps flying with nobody at the controls. Get up
and walk the cabin (GAP Cabin Walk) or get out at the airport: it switches the magnetos back on and
releases the parking brake that Immersive Vehicles sets when the pilot leaves, and keeps the
chunks around the plane loaded so it flies on even far from any player. Anyone aboard sees the HUD
(including a line with the departure and destination runway) and can use the autopilot keys and
`/autopilot off`.

**Stall protection** (all autopilot modes): if the angle of attack passes 11 degrees
(`protection.aoaLimit`; MTS wings stall at about 15-19), or the speed drops below 1.1 x stall speed, the autopilot lowers the nose, limits the bank
to 10 degrees and goes to full power until it's back under. Each time it trips it also learns that this
plane stalls faster than the lift model predicted and raises its circuit and approach speeds for the
rest of the flight. The game log says `Stall protection at ... km/h`. Slowing down is gradual, so the
speed no longer undershoots.

**No banking near the ground:**
- After an automatic takeoff the wings stay level, holding runway heading, until the plane is past
  the far end of the departure runway.
- **Airport Marker** (optional): place one at an airport to make a no-bank zone, a circle around it
  (default radius 300; right-click +50, sneak + right-click -50) reaching 100 blocks above it
  (`nobank.airportCeiling`). Rename it in an anvil to name the airport. Inside the zone the autopilot
  keeps the wings level and turns flat with rudder only, which is slow, so keep the zone tight
  around the runways: the approach entry point (600 out) and go-around circuit should lie outside it.

The approach and go-around don't look for terrain, so keep the 1-2 km in front of each runway end
clear.

Tuning: gains are in `serverconfig/ivautopilot-server.toml` inside the world folder
(`run/saves/<world>/serverconfig/` in the dev environment). If the nose oscillates, lower `pitch.kp`
or raise `pitch.kd`; if it settles too slowly or sags, raise `pitch.ki`. Takeoff: if a plane aborts
because it never reaches rotation speed, lower `takeoff.rotateMargin`; if it rotates and doesn't lift
off, raise it. Landing settings are in the `landing` section (glide slope, approach speed factor, flare
height, brakes, reverse thrust); heights there are of the wheels above the runway.

Development: `./gradlew runClient` also builds and loads the sibling GoldenAeroTransport1.20.1 pack
so there are planes to test with.

Flight recorder: while the autopilot is on, one `[FDR]` line a second goes to `run/logs/latest.log`
(phase, speed vs. target and stall speed, altitude, pitch, angle of attack, bank, trims, throttle,
flaps, gear).
