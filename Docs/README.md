# BlueTrace 文档导航

主线工作集 = **两份文件**（外加一份机器契约）；其余历史文档已归档 `legacy/`。

```
/SPEC.md                         ← 合并规格（做什么 / 通信协议 / 交互行为 / 数据模型 / 工程口径，自足）
Docs/
├── prototypes/
│   ├── v4_android.html          ← **当前** UI + 逐屏交互单一真源（底部三 Tab；37 屏 .screen-ux）
│   ├── v3_android.html · v3_ios.html   legacy 参考
│   └── legacy/  v1 · v2 · codex.html    历史版本
├── architecture/
│   └── bluetrace_v0.proto       ← protobuf 机器契约（SPEC §4 引用）
├── legacy/                      ← 全部历史文档归档（见 legacy/README.md）
│   ├── REQUIREMENTS.md · BlueTrace_Protocol.md · BlueTrace_Architecture.md
│   ├── BlueTrace_UI_Implementation.md · BlueTrace_CrossPlatform_Notes.md
│   ├── BlueTrace_Design_System.md · BlueTrace_V4_设计契约/决策追踪 · BlueTrace_设计审查
│   └── BlueTrace_PRD.md · BlueTrace_UX_Flows.md · BlueTrace_UI_Design.md · *_v1_*
└── assets/                      图标 / 截图 / svg
```

## 怎么读

| 你要… | 看 |
| --- | --- |
| 做什么 / 协议 / 交互 / 工程口径 | [`/SPEC.md`](../SPEC.md)（自足，一份搞定）|
| 界面长什么样 + 逐屏交互 | [`prototypes/v4_android.html`](prototypes/v4_android.html)（浏览器直接打开）|
| protobuf 消息契约 | [`architecture/bluetrace_v0.proto`](architecture/bluetrace_v0.proto) |
| 某个决策的历史来龙去脉 | [`legacy/`](legacy/README.md)（仅历史参考，口径以 SPEC 为准）|
| 让 agent 跑出第一版 app | [`agent_build_prompt_v1.md`](agent_build_prompt_v1.md)（Mock BLE · `/goal` 驱动的构建 prompt）|
| 让 agent 继续开发(收口+图标+启动页) | [`agent_build_prompt_v2.md`](agent_build_prompt_v2.md)（修结构性差距 + app/启动页图标 + P3/P4）|

## 真源分工

```text
SPEC.md（做什么 / 协议 / 工程口径） + v4_android.html 原型（长什么样 + 逐屏交互） → 直接开发
                         bluetrace_v0.proto（机器契约）
```

- 改 UI / 交互 → 只动 `v4_android.html`；改需求 / 协议 / 工程口径 → 只动 `SPEC.md`。
- 任何与 SPEC 冲突的历史文档，一律以 **SPEC（V4 + 2026-06-17 最新口径）** 为准。

## 看原型的方式

`prototypes/*.html` 是静态网页，浏览器直接打开（或 VSCode Live Preview）。需要界面图片时 `Ctrl+P` 存 PDF 或对每个手机框截图——**原型是图片来源，不要把静态图片当主版本**。
