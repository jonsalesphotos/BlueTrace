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

All design and planning materials are stored in [Docs](Docs).

- [Architecture Design](Docs/BlueTrace_Architecture.md)
- [UI Product and Interaction Notes](Docs/BlueTrace_UI_Design.md)
- [UI Technical Implementation Notes](Docs/BlueTrace_UI_Implementation.md)
- [UI Prototype](Docs/BlueTrace_UI_Prototype.html)
- [UI Prototype v2](Docs/BlueTrace_UI_Prototype_v2.html)
- [Codex UI Prototype](Docs/BlueTrace_UI_Codex_Prototype.html)

## Assets

Design screenshots, icons, and image references are also under [Docs](Docs):

- [App icon SVG](Docs/bluetrace_icon.svg)
- [Icon preview](Docs/%E5%9B%BE%E6%A0%87.png)
- [First-screen preview](Docs/%E9%A6%96%E5%B1%8F.png)
- [Reference images](Docs/pic)

## Repository Status

This repository currently contains design documents and prototypes only. Android source
code can be added later using the architecture and implementation documents as the build
guide.
