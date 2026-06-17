# BlueTrace

BlueTrace 是一个 Android BLE 数据采集 App 的设计与规划仓库。当前内容主要包括产品流程、UI 原型、架构设计和技术实施说明，目标是为后续 Kotlin、Jetpack Compose、Material 3 的 Android App 开发提供清晰依据。

English documentation: [README.md](README.md)

## 项目定位

BlueTrace 面向 BLE 传感器数据采集，一期核心场景是 PPG 信号评测。一次采集会话可以连接一台或多台设备，例如：

- DUT：待测设备，通常是自研 PPG 设备。
- REFERENCE：参考设备，例如标准 BLE 心率带。
- 未来扩展角色：血氧仪等其它 BLE 传感器。

架构上将 BLE 协议、数据解析、采集编排和文件落盘拆分，方便先完成 UI 与流程闭环，再逐步接入真实设备能力。

## 文档目录

主线工作集 = **两份文件**（外加一份机器契约）：

- **[`SPEC.md`](SPEC.md)** —— 合并规格（自足）：做什么 / BLE 通信协议 / 交互行为 / 数据与文件模型 / 工程要点。
- **[`Docs/prototypes/v4_android.html`](Docs/prototypes/v4_android.html)** —— UI + 逐屏交互单一真源（37 屏，每屏带 `.screen-ux` 交互块）。iOS 原型待补。
- **[`Docs/architecture/bluetrace_v0.proto`](Docs/architecture/bluetrace_v0.proto)** —— protobuf 机器契约（SPEC §4 引用）。

其余文档（原 REQUIREMENTS / PRD / UX_Flows / Protocol / Architecture / Design System / V4 契约与决策追踪）**已归档 [`Docs/legacy/`](Docs/legacy/README.md)**，内容已内联进 `SPEC.md`，仅作历史参考。完整导航见 [Docs/README.md](Docs/README.md)。

**原型（手机墙）：** 当前 **[v4 · Android](Docs/prototypes/v4_android.html)**（底部三 Tab：采集 / 数据 / 设置）；legacy 参考 [v3 Android](Docs/prototypes/v3_android.html) · [v3 iOS](Docs/prototypes/v3_ios.html)。

## 设计资源

图标与截图归档到 [Docs/assets](Docs/assets)：

- [App 图标 SVG](Docs/assets/bluetrace_icon.svg)
- [图标预览](Docs/assets/%E5%9B%BE%E6%A0%87.png)
- [首屏预览](Docs/assets/%E9%A6%96%E5%B1%8F.png)
- [参考图片](Docs/assets/pic)

## 当前状态

当前仓库主要保存设计文档和 HTML 原型，暂未包含 Android 工程源码。后续可以根据架构设计与实施说明继续创建 Android 项目。
