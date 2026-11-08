# HUD-01 changelog

One entry per iteration. Each iteration is tagged `vX.Y.Z` and published as a GitHub release with its APK.

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
