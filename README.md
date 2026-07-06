# BlueTrace

BlueTrace is a design and planning repository for an Android BLE data collection app.
The current materials describe the product flow, UI prototype, and implementation plan
for a Kotlin, Jetpack Compose, and Material 3 application focused on stable BLE sensor
collection sessions.

For Chinese documentation, see [README_cn.md](README_cn.md).

## Project Scope

BlueTrace is designed for BLE sensor data collection, with the first target scenario
being PPG evaluation. A session can collect data from one or more devices, such as:

- DUT: the device under test, typically a custom PPG device.
- REFERENCE: a standard heart-rate reference device, such as a BLE heart-rate belt.
- Future roles: additional BLE sensors such as pulse oximeters.

The architecture keeps BLE protocol handling, parsing, session orchestration, and file
persistence separated so the app can evolve from prototype UI to real device workflows.

## Documentation

The working set is **two files** (plus one machine contract):

- **[`SPEC.md`](SPEC.md)** — consolidated, self-sufficient spec: what to build, BLE protocol, interaction behaviour, data/file model, engineering notes.
- **[`Docs/prototypes/v4_android.html`](Docs/prototypes/v4_android.html)** — single source of truth for UI + per-screen UX (37 screens, each with a `.screen-ux` interaction block). iOS prototype TBD.
- **[`Docs/architecture/bluetrace_v0.proto`](Docs/architecture/bluetrace_v0.proto)** — machine-readable protobuf contract (referenced by SPEC §4).

Everything else (former REQUIREMENTS / PRD / UX_Flows / Protocol / Architecture / Design System / V4 contract & decision log) is **archived under [`Docs/legacy/`](Docs/legacy/README.md)** for history — its content is inlined into `SPEC.md`. Full map: [Docs/README.md](Docs/README.md).

**Prototypes:** current **[v4 · Android](Docs/prototypes/v4_android.html)** (bottom 3-tab IA: Collect / Data / Settings); legacy reference [v3 Android](Docs/prototypes/legacy/v3_android.html) · [v3 iOS](Docs/prototypes/legacy/v3_ios.html).

## Assets

Icons and screenshots are under [Docs/assets](Docs/assets):

- [App icon SVG](Docs/assets/bluetrace_icon.svg)
- [Icon preview](Docs/assets/%E5%9B%BE%E6%A0%87.png)
- [First-screen preview](Docs/assets/%E9%A6%96%E5%B1%8F.png)
- [Reference images](Docs/assets/pic)

## Implementation (v1 · Mock BLE)

The first Android version is implemented as a **KMP project** (BLE is mocked; real protocol
decoding is left behind an interface). Built strictly from `SPEC.md` + the v4 prototype.

### Modules

- **`:shared`** (`commonMain`, pure Kotlin, no Android deps, JVM-testable)
  - `protocol/` — frame-header / msgType stubs + `SampleDecoder` interface + `MockSampleDecoder` + `MockPacketCodec`
  - `ble/` — `BleClient` interface + `MockBleClient` (fake devices + continuous data + disconnect injection)
  - `session/` — `SessionController` interface + `DefaultSessionController` (single-channel orchestration, 3-state machine) + global diagnostics log
  - `data/` — okio raw-HEX / CSV writers, D-6 folder layout, `kotlinx.serialization` manifest, `SessionStore`
  - `domain/` — flat device model (DUT≤3 + reference≤1), `Subject`, `Mode`, `CollectType`, session entities + repository interfaces
- **`:app`** (Android) — Jetpack Compose UI (per-screen), `NavigationSuiteScaffold` 3-tab + type-safe routes, Koin DI, DataStore, MediaStore export, foreground service, permission/bluetooth gating. BLE is bound to `MockBleClient` (swap for the real Nordic impl later — interface unchanged).

### Tech stack (SPEC §10.2)

Kotlin 2.2 · AGP 9 · Compose + Material3 adaptive · Navigation Compose (type-safe `@Serializable` routes) · Koin · okio · kotlinx.serialization · DataStore · coroutines. `minSdk 29 / target·compile 36`.

### Build / test / run

```bash
./gradlew :shared:jvmTest        # shared logic unit tests (state machine, D-6 writers, manifest, MockBleClient)
./gradlew :app:assembleDebug     # build debug APK
./gradlew installDebug           # install to a connected device/emulator
```

### Deferred to later phases

Real BLE (Nordic) + Wire protocol decoding · sensor master-control / device-side algorithms (config A/B, placeholder) · device maintenance (time-sync / firmware / OTA, placeholder) · iOS · server / upload (phase 2). Entry points/placeholders exist and do not block the main flow.
