# HUD-01 changelog

One entry per iteration. Each iteration is tagged `vX.Y.Z` and published as a GitHub release with its APK.

## v0.4.0: Iteration 4, Evangelion styling and movable elements (ships with the v0.3.0 items below)
Changed files: `HudView.kt` (rewritten), `Layout.kt` (new), `EngineWatch.kt` (new), `MainActivity.kt`, `VehicleState.kt` (`Level.GOOD`), `app/build.gradle.kts` (version 0.4.0, code 4), `CHANGELOG.md`. `ObdLink.kt`, `SampleSource.kt` and the manifest are unchanged.

Styling:
- Palette: EVA-01 purple and neon green, NERV orange, near-black violet panels. Green still means "reading is fine", orange still means caution, red still means fault. RPM has no limits, so its bar uses neutral purple with a green leading segment.
- Speed in heavy serif numerals squeezed to 80% width (title-card look), inside slowly counter-rotating hexagon rings with corner brackets.
- Faint hexagon field across the background, a few cells tinted orange.
- Header: orange cut-corner HUD-01 tag, hexagon link light, scrolling orange hazard strip.
- RPM bar: 48 slanted segments, lit purple with a green head. Same non-linear dial and true tick positions as v0.2.0.
- Readouts: cut-corner panels with a status-coloured edge and a Japanese label (冷却水温, 油温, 負荷, 吸気圧, 電圧).
- Warning banner: hazard stripes top and bottom, 警告 block, text shrinks to fit instead of being cut off. Fault state blinks (unless animations are off in Android).
- Standby card when no source is linked and sample is off: HUD-01 title card with 待機中 / STANDBY.
- Mirror mode drops all decoration: only speed and RPM draw, so nothing but data glows on the windshield.

Movable elements (LAYOUT button):
- Eight elements move and resize independently: header, speed, RPM, coolant, oil, load, MAP, volts.
- Tap an element to select it, drag to move, SMALLER / BIGGER to resize (50% to 160%), RESET (selected) or RESET ALL, DONE to leave. Saved automatically.
- Normal view and mirror view keep separate layouts. Layout can be edited in mirror view too (screen shows flipped while editing).
- An element is always kept fully inside the safe area, so it cannot be dragged off-screen.
- The app now draws under the camera cutout and keeps all elements out of the cutout area and rounded corners.
- The warning banner is deliberately not movable, so an alert can never be hidden.

## v0.3.0: Trip tuning (was missing from the iteration-2 upload; included in v0.4.0)
- Added: "Engine running. Ready to move." log line, shown in green, the moment RPM is at or above 400 on two readings in a row. Fires once per start-up, not after every start-stop restart.
- Added: unplug reminder. When the car stops answering entirely (no RPM and no speed) for 5 s after the engine had been running, a cyan banner says to remove the OBD adapter. Tap to dismiss; it also clears by itself if the engine restarts or you relink. If the link drops after the engine was running (and you did not tap Unlink), the banner says the link was lost and to remove the adapter if the car is off.
- Start-stop is not treated as "car off": the engine computer still answers with 0 rpm during a stop.
- RPM rescale for 1.5-3k cruising: already done in v0.2.0 (1,000-3,500 rpm gets 70% of the dial).
- Coolant and oil limits: NOT added. The 103 C warning from the web prototype does not exist in the app (temperatures have no limits since v0.2.0). BMW service documents found describe the engine computer's coolant targets on other BMW engines (N-series) as roughly 104-112 C depending on driving mode; no B38-specific limit was found. Limits stay off until a B38 source is found.

## v0.2.0: Iteration 2, OBD link (live data from the car)
Changed files: `ObdLink.kt` (new), `VehicleState.kt`, `SampleSource.kt`, `MainActivity.kt`, `HudView.kt`, `AndroidManifest.xml`, `app/build.gradle.kts` (version), `CHANGELOG.md`.
- Classic Bluetooth (RFCOMM) link to the paired NexLink (found by name: NEXAS, OBD or ELM). Pair it once in Android settings, PIN 1234.
- Same ELM327 protocol as the web prototype: init, protocol lock via ATDPN, speed and RPM every cycle plus one rotating slow PID, 4 s timeout, retries, link dropped only after 5 misses in a row.
- On connect the app reads the car's own supported-PID list (01-20, 21-40, 41-60). Anything not listed, or giving no data 5 times with none ever seen, is shown as N/A and logged as unsupported. Nothing is estimated.
- Link/Unlink button; header shows LINK: CONNECTING or LIVE; one-line event log under the header (200 lines kept in memory for the later export feature).
- Sample mode and the live link are mutually exclusive.

Phase 2 rework (coolant, oil, RPM):
- RPM dial is non-linear: 0-1,000 rpm gets 8% of the width, 1,000-3,500 gets 70%, 3,500-7,000 gets 22%. Ticks are drawn at their true positions (500 minor, 1,000 major) so the scale cannot look linear, and the bar says "NON-LINEAR DIAL". The numeric RPM is large and always exact. Above 7,000 the bar stays full and "OVER DIAL SCALE" shows. 7,000 is a drawing limit, not an engine limit.
- No redline: no verified rev limit was found in public sources (only the rated-power band of 4,400-6,000 rpm), so none is drawn and the old orange/red RPM bands are gone. Dial mapping lives in `RpmScale`, completely separate from warnings.
- Coolant and oil no longer trigger warnings at any temperature. They show the measured value, a trend (least-squares over 2 minutes, needs 45 s of data; "STEADY" under 0.5 per minute, which is a display word only) and the maximum since the link started.
- Oil: shown only when the car supports PID 5C; otherwise N/A with "NOT SUPPORTED BY CAR". It is never estimated from coolant. It was read live on this car in the web test (36 °C when cold).
- The only remaining warning is system voltage below 11.8 V with the engine running. It is a provisional guess, not a BMW value, and is marked as such in the code.
- Sample mode: coolant still sweeps up and down so the trend can be seen, and voltage dips for 15 s every 2 minutes to show the warning banner.
- Still to do: read stored fault codes and genuine BMW/DME warnings for critical alerts, and research the oil circuit and B38 thermal strategy from BMW service information.

## v0.1.0: Iteration 1, foundation (HUD on the phone, sample data only)
Target: Samsung Galaxy S23, Android 16, landscape. Units: km/h, °C, kPa, V.

Added:
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`: Gradle project (AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 30, no extra libraries).
- `app/debug.keystore`: fixed debug signing key, so each new APK installs over the last one.
- `.github/workflows/build.yml`: builds the APK on every push to `main`; a `v*` tag also creates a GitHub release with the APK attached.
- `app/src/main/AndroidManifest.xml`: landscape-only activity, no permissions.
- `VehicleState.kt`: readings (null = not provided, never 0), warning limits, alert logic.
- `SampleSource.kt`: demo data generator (coolant sweeps past the limits so warnings can be seen).
- `HudView.kt`: the whole HUD drawn on one canvas: speed, 26-segment RPM bar, readouts, warning banner, mirror mode.
- `MainActivity.kt`: fullscreen immersive, screen kept on, 10 Hz update loop.

Not yet: OBD/Bluetooth, voice, LLM, Waze, app icon.

## Planned
- v0.2.0: classic Bluetooth link to the NexLink with live PIDs (replaces sample data when linked).
- v0.3.0+: voice in/out and waveform, local LLM narrator, Waze arrows.
