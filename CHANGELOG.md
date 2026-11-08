# HUD-01 changelog

One entry per iteration. Each iteration is tagged `vX.Y.Z` and published as a GitHub release with its APK.

## v0.4.0: Iteration 4, NERV operations-console look
Includes the planned v0.3.0 items (there is no separate v0.3.0 release).
Changed files: `HudView.kt` (rewritten), `MainActivity.kt`, `SampleSource.kt`, `app/build.gradle.kts` (version), `CHANGELOG.md`. New: `Gear.kt`, `Events.kt`, `THIRD_PARTY.md`, `app/src/main/assets/fonts/` (Noto Serif Display, JetBrains Mono, Saira Extra Condensed, with their OFL licences).

Trip events (formerly v0.3.0):
- Ready to move: once the engine has run above 400 rpm for 2 s, the log says "Engine running. Ready to move."
- Unplug reminder: when RPM stays at 0 or no data for 3 s after the engine was running, or the link drops after the engine was running (and you did not tap Unlink), an orange banner (stamp 通知, "notice") says to remove the OBD adapter, plus a log line. It stays for 2 minutes; tap the screen to dismiss. Only active on a live link, not in sample mode.

Gear (estimated):
- BimmerLink reads the gear from the gearbox computer with a BMW-specific request that is not public and has not been captured, so this build does not read it. Instead `GearEstimator` learns one rpm-per-km/h ratio per gear while you drive (needs a steady ratio for 6 readings, speed over 20 km/h, and a ratio seen 20 times before it counts as a gear). Gear 1 is the highest ratio learned. Shown bottom right of the speed panel as "GEAR EST"; "-" when clutch is in, between gears or not yet learned. Learned ratios are saved on the phone, so it only has to learn once. Sample mode uses its own separate estimator.
- Tested on synthetic data only (it learned all 6 pretend gears); not yet tried on the car.

- Design tokens, type pairing and patterns follow TheGreatGildo/nerv-ui (MIT): black void, orange for labels, green only for a live link, cyan for MAP, red only for faults, panels with orange title bars and Japanese sub-labels, segmented bars, hazard-stripe alert banners, CRT scanlines, vignette, slow scan line and faint flicker (phone view only; off in mirror mode and when animations are turned off).
- Layout like the reference photo: left SYSTEM STATUS panel, a bracketed centre panel with a huge compressed-serif speed numeral, right column with emblem, ENGINE LOAD and EVENT LOG, and a full-width RPM block at the bottom (same non-linear dial and ticks as before).
- Boot sequence: panels cut in one after another with a short flicker.
- Fault state: secondary panels drop away, the centre panel widens and the numeral turns red; the stamp reads 非常事態. Caution keeps the layout and shows 警告. 出撃待命 ("sortie standby") shows while no data source is linked.
- Mirror mode: black void with only the centre panel, RPM block and alerts; no effects.
- Safe-area handling: content stays clear of the camera cut-out on either side (this addresses elements being cut off; user-movable elements are still planned).
- Emblem slot: add your own `res/drawable/emblem` image and it replaces the placeholder hexagon. No NERV logo is included. If you own MatissePro-EB.otf, drop it in `assets/fonts/` and it is used for numerals and titles.

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
