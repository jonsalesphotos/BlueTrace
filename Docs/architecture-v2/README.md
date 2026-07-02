# architecture-v2 · 新一轮架构设计讨论区

> **本文件夹是全新的设计讨论区**（建于 2026-07-02），与 `Docs/architecture`、`Docs/legacy`、`Docs/_build_notes` 等历史文件隔离。
> 历史结论只在 [00_prior_discussions.md](00_prior_discussions.md) 中盘点引用，不在此处重复维护；新讨论一律落在本文件夹。

## 背景

BlueTrace 一期（Mock 链路）已完成 M1–M6，UI 按 `Docs/prototypes/v4_android.html` 落地并真机验证。
当前推进的三件事都卡在同一个位置：**真实 BLE 协议与其接入架构**。

1. **S7 项目的常规质量通信协议**要接进来，而且未来不同项目会有不同的通道/解析器 → 需要**可注册的协议架构**；
2. **离线采集 / 在线采集的 BLE 协议还没设计**（采集主界面「离线采集」目前是占位 Toast「离线采集待协议支持」，见 `CollectHomeScreen.kt:160`）→ 需要一套**适合 IMU 等高频数据采集的全新协议**（protobuf 交互 + TLV 头部 + 分包）；
3. **手表控制界面**可以准备做了 → 需要一份**适合重构、未来支持 KMP** 的落地规划。

## 文档索引

| 文档 | 内容 | 状态 |
|------|------|------|
| [00_prior_discussions.md](00_prior_discussions.md) | 历史讨论盘点：项目里已有哪些协议/架构结论，哪些主题空白 | ✅ |
| [01_implementation_review.md](01_implementation_review.md) | V4 原型 vs 实机逐屏对比报告（ADB 实拍，小米 M2101K9C） | ✅ |
| [02_parser_registry_design.md](02_parser_registry_design.md) | **可注册通道解析器架构**（ProtocolProfile / ChannelParser / Registry，S7 接入示例） | ✅ 设计稿 |
| [03_collect_protocol_design.md](03_collect_protocol_design.md) | **采集链路协议 BTCP/1**（在线流式 + 离线回传，TLV+protobuf+分包） | ✅ 设计稿 |
| [04_watch_control_plan.md](04_watch_control_plan.md) | **手表控制界面规划**（KMP-ready 分层 + 里程碑） | ✅ 规划 |
| [index.html](index.html) | 以上全部内容的 HTML 汇总报告（含截图画廊，可直接浏览器打开） | ✅ |
| `assets/device_20260702/` | 本轮 ADB 实机截图 22 张 | ✅ |
| [s7/protocol-spec.md](s7/protocol-spec.md) | **S7 · B2A 协议实现规格**（收敛稿，源文档 9 代理深读交叉校验） | ✅ |
| [s7/completeness-audit.md](s7/completeness-audit.md) | S7 协议文档完整性审计（十项判定 + 矛盾消歧 + 待补清单） | ✅ |
| [s7/plan.md](s7/plan.md) | 设备维护（DUT）控制台实施计划表（P1–P15） | ✅ 已执行 |
| `s7/_raw/` · `s7/assets/` | 协议提取原始材料 9 份 · 控制台实机验证截图 | ✅ |

## 阅读顺序建议

首次阅读：`index.html`（总览）→ `02`（架构地基）→ `03`（协议细节）→ `04`（手表控制）。
评审协议时请对照权威事实源：`SPEC.md §4` 与 `Docs/architecture/bluetrace_v0.proto`（v0.1 草案，未冻结）。

## 命名约定

- **BTCP/1**（BlueTrace Collection Protocol v1）：本轮设计的采集链路协议代号，冻结前均为草案。
- **Profile**：一个「项目/设备族」的协议接入包（通道声明 + 解析器 + 命令编码器），如 `BlueTraceV0Profile`、`S7QualityProfile`、`HrsProfile`。
