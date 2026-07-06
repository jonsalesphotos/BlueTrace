# BlueTrace 文档导航

> 真源分工：**做什么/协议/工程口径 看 [`/SPEC.md`](../SPEC.md)；界面长什么样 看 [`prototypes/v4_android.html`](prototypes/v4_android.html)**（37 屏逐屏交互）。
> 其余文档都是过程与派生产物；与 SPEC 冲突时以 SPEC（V4 + 2026-06-17 口径）为准。

## 目录结构

```
Docs/
├── 里程碑与进度.md               ← 高层阶段 M1–M9 与当前状态
├── CHANGELOG.md                  ← 逐轮修改记录（v1–v10 + 文档/回归轮次）
├── context/
│   └── BlueTrace开发_上下文.md   ← 活文档：走到哪了 / 关键决策 / 坑 / 下一步（接手先读这份）
├── 代码审查报告_20260706.md      ← 四路审查 52 条（修复已按波次①–④收官，余低优先项）
├── 真机修复prompt_复用.md        ← 真机发现问题 → 让 agent 修复的复用模板
├── prototypes/
│   └── v4_android.html           ← 当前 UI 真源（勿改名：全仓引用）
├── 设计/                         ← 设计验收三件套
│   ├── 设计审查报告_v6.md        ← 46 屏设计审查（色彩/副标/组件一致性）
│   ├── 设计稿画廊_v6.html        ← V4 设计稿截图集（浏览器打开）
│   └── 设计稿与真机对比_v2.html  ← 设计稿 ↔ 真机 A/B 并排对比（浏览器打开）
├── UWTP/                         ← UWTP 统一可穿戴传输协议（跨项目标准，独立目录）
│   ├── README.md                 ← 家族索引与演进路线
│   ├── UWTP_BLE_Protocol_Design_V0.99.{md,html} + uwtp_v0.99_draft.proto ← 当前基线（冻结候选）
│   ├── UHTP_BLE_Protocol_Design_V4.md ← 前身（历史，勿作基线）
│   └── assets/gen_uwtp_examples.py    ← 示例帧生成脚本
├── architecture/                 ← 唯一架构目录（v2 已并入）
│   ├── README.md                 ← 架构目录导航：文档角色 + 三线协议关系（先读）
│   ├── 架构评估_20260706.{md,html} ← 架构评估与演进路线（波次A/B 依据，P2 项在册）
│   ├── bluetrace_v0.proto        ← 自研 protobuf 机器契约（v0.1 草案，SPEC §4 引用，勿改路径）
│   ├── BLE协议帧规格_开发者版.{md,html} ← 自研帧规格实现视角（布局/位图/实例包/状态机）
│   ├── 02_parser_registry_design.md ← 可注册协议架构（R1–R3 已落码，R4/R5 蓝图）
│   ├── 03_collect_protocol_design.md + btcp1_draft.proto ← 自研采集协议候选（M7 冻结二选一之一）
│   ├── 存储与日志设计.md         ← 存储/日志重构方案（v7 已落地）
│   ├── scenes.json               ← 场景词表（与 app assets 同源，勿改路径）
│   └── s7/                       ← S7 手表协议线（独立 CHANGELOG）
│       ├── S7协议共识规格.{md,html}          ← 共识稿（B2A + 采集固件协议，以此为准）
│       ├── protocol-b2a / protocol-zqdata    ← 现网协议逐字节规格
│       ├── protocol-zqdata-uhtp-v1.{md,html} + zqdata_uhtp_v1_draft.proto ← 下一代重设计（待固件评审，M7 候选之二）
│       └── protocol-spec.md · command-status.md · completeness-audit.md · assets/
├── assets/                       ← 图标 / 截图（screenshots_v6、screenshots_device、pic 参考等）
├── 归档/                         ← 唯一历史桶（冻结不维护，仅考古）
│   ├── 构建prompt/               ← agent_build_prompt_v1–v7（各轮构建输入）
│   ├── 构建笔记/                 ← v1 实现笔记、v3 设计差异
│   ├── 架构讨论区_v2/            ← 原 architecture-v2 讨论区壳（00/01/04 盘点与已实施规划）
│   ├── 历史原型/                 ← v1–v3 原型 HTML
│   ├── 历史规格_v1-v3/           ← 原 legacy/（REQUIREMENTS/PRD/旧协议/设计系统等）
│   ├── s7协议工作底稿/           ← s7 协议线过程件（plan/review/_raw，2026-07-06 归档）
│   └── compare_design_vs_device.html ← 旧版对比页
└── （s7 过程件 plan/review/_raw 已归档；completeness-audit 留 s7/ 作活缺口清单）
```

## 怎么读

| 你要… | 看 |
| --- | --- |
| 接手开发 / 了解现状与下一步 | [`context/BlueTrace开发_上下文.md`](context/BlueTrace开发_上下文.md) |
| 做什么 / 协议 / 交互 / 工程口径 | [`/SPEC.md`](../SPEC.md)（自足） |
| 高层进度 / 里程碑 | [`里程碑与进度.md`](里程碑与进度.md) |
| 每一轮改了什么 | [`CHANGELOG.md`](CHANGELOG.md) |
| 界面长什么样 + 逐屏交互 | [`prototypes/v4_android.html`](prototypes/v4_android.html)（浏览器直接打开） |
| 设计稿 vs 真机对比 | [`设计/设计稿与真机对比_v2.html`](设计/设计稿与真机对比_v2.html) |
| 代码还有哪些问题要修 | [`代码审查报告_20260706.md`](代码审查报告_20260706.md)（波次①–④已收官，看遗留清单） |
| 自研 DUT 协议（App↔固件） | [`architecture/bluetrace_v0.proto`](architecture/bluetrace_v0.proto) + [`architecture/BLE协议帧规格_开发者版.md`](architecture/BLE协议帧规格_开发者版.md) + [`architecture/03_collect_protocol_design.md`](architecture/03_collect_protocol_design.md) |
| **UWTP 传输协议（跨项目标准）** | [`UWTP/`](UWTP/README.md)（V0.99 冻结候选为准；前身 UHTP V4 同目录） |
| S7 手表协议（B2A / zqdata 现网 + Profile） | [`architecture/s7/`](architecture/s7/)（共识稿为准；protocol-zqdata-uhtp-v1 待按 UWTP V0.99 改写为 S7 采集 Profile） |
| M7 协议接入怎么做 | [`architecture/02_parser_registry_design.md`](architecture/02_parser_registry_design.md)（可注册协议架构） |
| 真机用出问题了要修 | [`真机修复prompt_复用.md`](真机修复prompt_复用.md)（填【问题】模板） |
| 历史决策来龙去脉 | [`归档/`](归档/)（唯一历史桶，口径以 SPEC 为准） |

## 约定

- 改 UI/交互 → 只动 `prototypes/v4_android.html`；改需求/协议/工程口径 → 只动 `/SPEC.md`。
- 每轮实质性改动收尾 → 记 `CHANGELOG.md`；任务线推进 → 更新 `context/` 上下文文档（见全局 skill docs-convention）。
- 机器契约与代码同源文件（`bluetrace_v0.proto`、`scenes.json`、`v4_android.html`）**路径冻结勿动**；`CHANGELOG.md`/`README.md` 保留英文名（惯例）；其余新文档一律中文命名。
- 历史文档只进 `归档/`（唯一历史桶，冻结不维护）；不再新建 legacy/_build_notes 类平行历史目录。
- 原型是静态网页，浏览器直接打开；需要图片时对手机框截图——**原型是图片来源，不要把静态截图当主版本**。
