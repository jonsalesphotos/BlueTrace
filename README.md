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

All design and planning materials live under [Docs](Docs), organized by concern.
See [Docs/README.md](Docs/README.md) for the full map.

**Start here — control document:** [REQUIREMENTS.md](REQUIREMENTS.md) is the single source of
truth and plan tracker. It consolidates requirements, milestones, the requirement↔phase status
matrix, and the resolved key decisions (D-1…D-10). Detailed docs below are downstream of it.

**Product (what & why):**
- [Product Requirements (PRD)](Docs/legacy/BlueTrace_PRD.md) _(legacy — requirements now in [REQUIREMENTS.md](REQUIREMENTS.md))_
- [UX / Interaction Flows](Docs/legacy/BlueTrace_UX_Flows.md) _(legacy — flows now in the [prototype](Docs/prototypes/v4_android.html))_
- [Design System](Docs/product/BlueTrace_Design_System.md)
- [V4 Design Contract](Docs/reviews/BlueTrace_V4_设计契约_2026-06-16.md) — authoritative bottom-3-tab IA & scope convergence (V4)

**Prototypes (the phone walls):**
- **Current — [v4 · Android](Docs/prototypes/v4_android.html)**: bottom 3-tab IA (Collect / Data / Settings). iOS v4 TBD.
- Legacy reference: [v3 · Android](Docs/prototypes/v3_android.html) · [v3 · iOS](Docs/prototypes/v3_ios.html) — reuse mature exception / collection-runtime / recovery visuals only, not the wizard IA.
- Archive: [v1](Docs/prototypes/legacy/v1.html) · [v2](Docs/prototypes/legacy/v2.html) · [Codex](Docs/prototypes/legacy/codex.html)

**Architecture (how):**
- [Android Architecture](Docs/architecture/BlueTrace_Architecture.md)
- [UI Implementation Notes](Docs/architecture/BlueTrace_UI_Implementation.md)
- [Cross-Platform Notes (Android + iOS)](Docs/architecture/BlueTrace_CrossPlatform_Notes.md)
- [Device Protocol v0](Docs/architecture/BlueTrace_Protocol.md) · [bluetrace_v0.proto](Docs/architecture/bluetrace_v0.proto)

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
