# BlueTrace 文档导航

> 真源分工：**做什么/协议/工程口径 看 [`/SPEC.md`](../SPEC.md)；界面长什么样 看 [`prototypes/v4_android.html`](prototypes/v4_android.html)**（37 屏逐屏交互）。
> 其余文档都是过程与派生产物；与 SPEC 冲突时以 SPEC（V4 + 2026-06-17 口径）为准。

## 目录结构

```
Docs/
├── 里程碑与进度.md               ← 高层阶段 M1–M9 与当前状态
├── CHANGELOG.md                  ← 逐轮修改记录（v1–v8 + 文档轮次）
├── context/
│   └── BlueTrace开发_上下文.md   ← 活文档：走到哪了 / 关键决策 / 坑 / 下一步（接手先读这份）
├── 代码审查报告_20260706.md      ← 架构·界面·交互·s7 分支 四路审查（52 条，修复按波次推进）
├── 设计审查报告_v6.md            ← 46 屏设计审查（色彩/副标/组件一致性）
├── 设计稿画廊_v6.html            ← V4 设计稿截图集（浏览器打开）
├── 设计稿与真机对比_v2.html      ← 设计稿 ↔ 真机 A/B 并排对比（浏览器打开）
├── 真机修复prompt_复用.md        ← 真机发现问题 → 让 agent 修复的复用模板
├── prototypes/
│   ├── v4_android.html           ← 当前 UI 真源（勿改名：全仓引用）
│   └── legacy/                   ← v1–v3 历史原型
├── architecture/                 ← 自研 DUT 协议与架构（协议文档线活动区）
│   ├── bluetrace_v0.proto        ← protobuf 机器契约（待冻结）
│   ├── BLE协议帧规格_开发者版.{md,html} ← 帧规格开发者版（布局/位图/实例包）
│   ├── 存储与日志设计.md ← 存储/日志重构方案（v7 已落地）
│   └── scenes.json               ← 场景词表（与 app assets 同源）
├── architecture-v2/
│   └── s7/                       ← S7 手表协议规格（B2A/zqdata/共识稿，独立 CHANGELOG）
├── assets/                       ← 图标 / 截图（screenshots_v6、device_v6 等）
├── 归档/                         ← 已消费的过程文档（不再维护）
│   ├── 构建prompt/               ← agent_build_prompt_v1–v7（各轮构建输入，已执行完毕）
│   ├── 构建笔记/                 ← v1 实现笔记、v3 设计差异
│   └── compare_design_vs_device.html ← 旧版对比页（被 v2 取代）
└── legacy/                       ← v1–v3 时代规格/PRD/协议等历史文档（口径已废，仅考古）
```

## 怎么读

| 你要… | 看 |
| --- | --- |
| 接手开发 / 了解现状与下一步 | [`context/BlueTrace开发_上下文.md`](context/BlueTrace开发_上下文.md) |
| 做什么 / 协议 / 交互 / 工程口径 | [`/SPEC.md`](../SPEC.md)（自足） |
| 高层进度 / 里程碑 | [`里程碑与进度.md`](里程碑与进度.md) |
| 每一轮改了什么 | [`CHANGELOG.md`](CHANGELOG.md) |
| 界面长什么样 + 逐屏交互 | [`prototypes/v4_android.html`](prototypes/v4_android.html)（浏览器直接打开） |
| 设计稿 vs 真机对比 | [`设计稿与真机对比_v2.html`](设计稿与真机对比_v2.html) |
| 代码还有哪些问题要修 | [`代码审查报告_20260706.md`](代码审查报告_20260706.md)（按波次①–④推进） |
| 自研 DUT 协议（App↔固件） | [`architecture/bluetrace_v0.proto`](architecture/bluetrace_v0.proto) + [`architecture/BLE协议帧规格_开发者版.md`](architecture/BLE协议帧规格_开发者版.md) |
| S7 手表协议（B2A / zqdata） | [`architecture-v2/s7/`](architecture-v2/s7/)（共识稿 S7协议共识规格 为准） |
| 真机用出问题了要修 | [`真机修复prompt_复用.md`](真机修复prompt_复用.md)（填【问题】模板） |
| 历史决策来龙去脉 | [`legacy/`](legacy/README.md) 与 [`归档/`](归档/)（仅参考，口径以 SPEC 为准） |

## 约定

- 改 UI/交互 → 只动 `prototypes/v4_android.html`；改需求/协议/工程口径 → 只动 `/SPEC.md`。
- 每轮实质性改动收尾 → 记 `CHANGELOG.md`；任务线推进 → 更新 `context/` 上下文文档（见全局 skill docs-convention）。
- `v4_android.html`、`CHANGELOG.md`、`README.md` 保留英文文件名（全仓/工具链引用面大）；其余新文档一律中文命名。
- 原型是静态网页，浏览器直接打开；需要图片时对手机框截图——**原型是图片来源，不要把静态截图当主版本**。
