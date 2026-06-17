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

**Prototypes:** current **[v4 · Android](Docs/prototypes/v4_android.html)** (bottom 3-tab IA: Collect / Data / Settings); legacy reference [v3 Android](Docs/prototypes/v3_android.html) · [v3 iOS](Docs/prototypes/v3_ios.html).

## Assets

Icons and screenshots are under [Docs/assets](Docs/assets):

- [App icon SVG](Docs/assets/bluetrace_icon.svg)
- [Icon preview](Docs/assets/%E5%9B%BE%E6%A0%87.png)
- [First-screen preview](Docs/assets/%E9%A6%96%E5%B1%8F.png)
- [Reference images](Docs/assets/pic)

## Repository Status

This repository currently contains design documents and prototypes only. Android source
code can be added later using the architecture and implementation documents as the build
guide.
