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

所有文档按**关注点分层**放入 [Docs](Docs)，完整导航见 [Docs/README.md](Docs/README.md)。

**产品（做什么 / 为什么）：**
- [产品需求 PRD](Docs/legacy/BlueTrace_PRD.md) （legacy 归档 · 需求以 [REQUIREMENTS.md](REQUIREMENTS.md) 为准）
- [交互 / UX 规格](Docs/legacy/BlueTrace_UX_Flows.md) （legacy 归档 · 交互以 [原型](Docs/prototypes/v4_android.html) 为准）
- [设计系统](Docs/product/BlueTrace_Design_System.md)

**原型（手机墙）：**
- [v3 · Android](Docs/prototypes/v3_android.html) · [v3 · iOS](Docs/prototypes/v3_ios.html)
- 历史版本：[v1](Docs/prototypes/legacy/v1.html) · [v2](Docs/prototypes/legacy/v2.html) · [Codex](Docs/prototypes/legacy/codex.html)

**技术架构（怎么做）：**
- [Android 架构设计](Docs/architecture/BlueTrace_Architecture.md)
- [UI 技术实施说明](Docs/architecture/BlueTrace_UI_Implementation.md)
- [跨平台技术说明（Android + iOS）](Docs/architecture/BlueTrace_CrossPlatform_Notes.md)

## 设计资源

图标与截图归档到 [Docs/assets](Docs/assets)：

- [App 图标 SVG](Docs/assets/bluetrace_icon.svg)
- [图标预览](Docs/assets/%E5%9B%BE%E6%A0%87.png)
- [首屏预览](Docs/assets/%E9%A6%96%E5%B1%8F.png)
- [参考图片](Docs/assets/pic)

## 当前状态

当前仓库主要保存设计文档和 HTML 原型，暂未包含 Android 工程源码。后续可以根据架构设计与实施说明继续创建 Android 项目。
