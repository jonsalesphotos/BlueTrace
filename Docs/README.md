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
└── assets/                      图标 / 截图 / svg（pic/ = Creek ACQ 参考 App 截图，见 assets/pic/README.md）
```

## 怎么读

| 你要… | 看 |
| --- | --- |
| 做什么 / 协议 / 交互 / 工程口径 | [`/SPEC.md`](../SPEC.md)（自足，一份搞定）|
| 开发进度 / 里程碑 / 下一步 | [`MILESTONES.md`](MILESTONES.md)（P1–P4 ✅ · P5 待协议冻结 · iOS/服务器 二期）|
| 界面长什么样 + 逐屏交互 | [`prototypes/v4_android.html`](prototypes/v4_android.html)（浏览器直接打开）|
| 设计稿 vs 真机 逐屏对比 | [`compare_design_vs_device.html`](compare_design_vs_device.html)（A/B 并排 21 对，浏览器打开，点图放大）|
| protobuf 消息契约 | [`architecture/bluetrace_v0.proto`](architecture/bluetrace_v0.proto) |
| 存储/日志 重构方案(SQLDelight + .log) | [`architecture/storage_logging_design.md`](architecture/storage_logging_design.md)（v8 · 已过多视角审查修订）|
| 某个决策的历史来龙去脉 | [`legacy/`](legacy/README.md)（仅历史参考，口径以 SPEC 为准）|
| 让 agent 跑出第一版 app | [`agent_build_prompt_v1.md`](agent_build_prompt_v1.md)（Mock BLE · `/goal` 驱动的构建 prompt）|
| 让 agent 继续开发(收口+图标+启动页) | [`agent_build_prompt_v2.md`](agent_build_prompt_v2.md)（修结构性差距 + app/启动页图标 + P3/P4）|
| 让 agent 做设计稿↔真机对照收敛 | [`agent_build_prompt_v3.md`](agent_build_prompt_v3.md)（逐屏对照 screenshots/ ↔ screenshots_device/，冲突先确认）|
| 让 agent 收尾 GNSS 线 + Q1 入口迁移 | [`agent_build_prompt_v4.md`](agent_build_prompt_v4.md)（GNSS WIP 收尾 + 设置去开关→采集类型勾选 + 真机核验 gps.csv）|
| 让 agent 同步主界面三屏(采集/数据/设置) | [`agent_build_prompt_v5.md`](agent_build_prompt_v5.md)（设计↔实现收敛第一步 · Workflow 编排：并行 diff→串行 apply→对抗 verify）|
| 让 agent 同步剩余页面(场景模型+前置流+摘要/详情编辑) | [`agent_build_prompt_v6.md`](agent_build_prompt_v6.md)（场景模型 scenes.json + 命名落地 + 场景选择页/用户选择编辑/摘要详情编辑 · Workflow 编排 · **真机必测**）|
| 让 agent 落地 存储&日志 重构(基础设施轮) | [`agent_build_prompt_v7.md`](agent_build_prompt_v7.md)（SQLDelight 用户表 + 滚动 .log；实现 [`storage_logging_design.md`](architecture/storage_logging_design.md) · Workflow 编排 · **真机必测**）|
| 真机使用中发现问题 → 让 agent 修复(复用) | [`agent_fix_prompt.md`](agent_fix_prompt.md)（填【问题】模板 → 真机复现取证(日志/DB/manifest)→ 根因→最小修→单测+真机回归→push；屏↔代码速查）|

## 真源分工

```text
SPEC.md（做什么 / 协议 / 工程口径） + v4_android.html 原型（长什么样 + 逐屏交互） → 直接开发
                         bluetrace_v0.proto（机器契约）
```

- 改 UI / 交互 → 只动 `v4_android.html`；改需求 / 协议 / 工程口径 → 只动 `SPEC.md`。
- 任何与 SPEC 冲突的历史文档，一律以 **SPEC（V4 + 2026-06-17 最新口径）** 为准。

## 看原型的方式

`prototypes/*.html` 是静态网页，浏览器直接打开（或 VSCode Live Preview）。需要界面图片时 `Ctrl+P` 存 PDF 或对每个手机框截图——**原型是图片来源，不要把静态图片当主版本**。
