# BlueTrace 文档导航

本目录按**关注点分层**组织：产品需求、视觉原型、技术架构彼此独立，互不污染。

```
Docs/
├── product/          产品 / 设计文字（相对稳定的"契约"）
│   └── BlueTrace_Design_System.md  设计系统：色板/字号/组件 + Android↔iOS 映射
├── prototypes/       视觉原型（会频繁改的"活产物" = 手机墙）
│   ├── v4_android.html             **v4 · Android（当前）**：底部三 Tab（采集/数据/设置）
│   ├── v3_android.html             v3 · Android（**legacy 参考**：异常态/采集运行/恢复流程复用其外观）
│   ├── v3_ios.html                 v3 · iOS（legacy 参考；**v4_ios 待补**）
│   └── legacy/  v1.html · v2.html · codex.html（历史版本归档）
├── architecture/     技术实现（怎么做）
│   ├── BlueTrace_Architecture.md           Android 详版架构
│   ├── BlueTrace_UI_Implementation.md       UI 技术实施
│   ├── BlueTrace_CrossPlatform_Notes.md     Android + iOS 跨平台技术分叉
│   ├── BlueTrace_Protocol.md                设备通信协议 v0（标准头部+分片+protobuf）
│   └── bluetrace_v0.proto                   协议 payload 权威 .proto 契约
├── reviews/          设计审查与收敛契约（V4）
│   ├── BlueTrace_设计审查_2026-06-16.md            设计审查报告（10 维）
│   └── BlueTrace_V4_设计契约_2026-06-16.md          V4 收敛契约（权威 IA / 范围 / 冲突修正）
├── legacy/           被取代的旧文档
│   ├── BlueTrace_PRD.md                            （归档 2026-06-17 · 需求以 ../REQUIREMENTS.md 为准）
│   ├── BlueTrace_UX_Flows.md                       （归档 2026-06-17 · 交互以 prototypes/v4_android.html 为准）
│   ├── BlueTrace_UI_Design.md                      （更早的 UI 文字，已被取代）
│   └── BlueTrace_Architecture_v1_single_device.md  （单设备第一版，已由 architecture/BlueTrace_Architecture.md 取代）
└── assets/           图标 / 截图 / svg
```

## 怎么读（按角色）

| 你是… | 先读 |
| --- | --- |
| 产品 / 业务 / 第一次做需求 | [`../REQUIREMENTS.md`](../REQUIREMENTS.md) → [`prototypes/v4_android.html`](prototypes/v4_android.html)（PRD / UX_Flows 已归档 `legacy/`） |
| 设计 / 前端 | `product/BlueTrace_Design_System.md` + `prototypes/v4_android.html`（当前；v3 仅 legacy 参考）+ `reviews/BlueTrace_V4_设计契约_2026-06-16.md` |
| Android 开发 | `architecture/BlueTrace_Architecture.md` |
| 跨平台 / iOS 开发 | `architecture/BlueTrace_CrossPlatform_Notes.md` |
| 固件 / 协议对接 | `architecture/BlueTrace_Protocol.md` + `architecture/bluetrace_v0.proto` |

## 四层关系

```text
REQUIREMENTS（需求）──▶ Design System + v4_android.html 原型（视觉 + 逐屏交互）──▶ Architecture（实现）
      做什么                   长什么样 / 怎么操作 / 用什么搭                    怎么落地

（PRD / UX_Flows 已归档 legacy/，仅作历史参考；原型现内置每屏 UX 交互规格）
```

改东西时遵循"就近最小改动"：能改 token 不改组件，能改需求文字不改原型代码。

## 看原型的方式

`prototypes/*.html` 是静态网页，直接用浏览器打开即可（或 VSCode 里 Live Preview）。
需要"界面图片"时，浏览器 `Ctrl+P` 存 PDF，或对每个手机框截图——**原型是图片的来源，不要把静态图片当主版本**。
