# BlueTrace 文档导航

本目录按**关注点分层**组织：产品需求、视觉原型、技术架构彼此独立，互不污染。

```
Docs/
├── product/          产品 / 设计文字（相对稳定的"契约"）
│   ├── BlueTrace_PRD.md            产品需求：做什么、为谁、为什么、范围/优先级（平台无关）
│   ├── BlueTrace_UX_Flows.md       交互规格：页面流、状态机、边界、平台差异
│   └── BlueTrace_Design_System.md  设计系统：色板/字号/组件 + Android↔iOS 映射
├── prototypes/       视觉原型（会频繁改的"活产物" = 手机墙）
│   ├── v3_android.html             v3 · Android（Material 3，含主界面 Home，18 屏 + 协议说明）
│   ├── v3_ios.html                 v3 · iOS（HIG 自适应，关键分叉屏）
│   └── legacy/  v1.html · v2.html · codex.html（历史版本归档）
├── architecture/     技术实现（怎么做）
│   ├── BlueTrace_Architecture.md           Android 详版架构
│   ├── BlueTrace_UI_Implementation.md       UI 技术实施
│   ├── BlueTrace_CrossPlatform_Notes.md     Android + iOS 跨平台技术分叉
│   ├── BlueTrace_Protocol.md                设备通信协议 v0（标准头部+分片+protobuf）
│   └── bluetrace_v0.proto                   协议 payload 权威 .proto 契约
├── legacy/           被取代的旧文档
│   ├── BlueTrace_UI_Design.md                      （已由 product/PRD + UX_Flows 取代）
│   └── BlueTrace_Architecture_v1_single_device.md  （单设备第一版，已由 architecture/BlueTrace_Architecture.md 取代）
└── assets/           图标 / 截图 / svg
```

## 怎么读（按角色）

| 你是… | 先读 |
| --- | --- |
| 产品 / 业务 / 第一次做需求 | `product/BlueTrace_PRD.md` → `product/BlueTrace_UX_Flows.md` |
| 设计 / 前端 | `product/BlueTrace_Design_System.md` + `prototypes/v3_*.html` |
| Android 开发 | `architecture/BlueTrace_Architecture.md` |
| 跨平台 / iOS 开发 | `architecture/BlueTrace_CrossPlatform_Notes.md` |
| 固件 / 协议对接 | `architecture/BlueTrace_Protocol.md` + `architecture/bluetrace_v0.proto` |

## 四层关系

```text
PRD（需求）──▶ UX Flows（交互）──▶ Design System + 原型（视觉）──▶ Architecture（实现）
   做什么          怎么操作            长什么样、用什么搭           怎么落地
```

改东西时遵循"就近最小改动"：能改 token 不改组件，能改需求文字不改原型代码。

## 看原型的方式

`prototypes/*.html` 是静态网页，直接用浏览器打开即可（或 VSCode 里 Live Preview）。
需要"界面图片"时，浏览器 `Ctrl+P` 存 PDF，或对每个手机框截图——**原型是图片的来源，不要把静态图片当主版本**。
