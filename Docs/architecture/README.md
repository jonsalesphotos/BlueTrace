# architecture/ 目录导航（2026-07-06）

> 唯一架构目录（原 architecture-v2 已并入）。本页回答两件事：**每份文档是什么角色、现在还算不算数**；**三条协议线是什么关系**。
> 真源口径不变：规格冲突时 [`/SPEC.md`](../../SPEC.md) ＞ 各设计稿。

## 1. 文档清单与状态

| 文档 | 角色 | 状态 |
| --- | --- | --- |
| [架构评估_20260706.md](架构评估_20260706.md) | 全仓架构评估与演进路线（含 §0 机制速览，面向非 Android 背景） | **活**——波次A/B 已按此收官，P2 卫生项在册 |
| [02_parser_registry_design.md](02_parser_registry_design.md) | 可注册协议架构（ProtocolProfile/Registry/ParserHost） | **R1–R3 已落码 ✅**（`shared.protocol.registry`）；R4/R5 蓝图部分仍算数 |
| [存储与日志设计.md](存储与日志设计.md) | 存储/日志重构方案（SQLDelight + 滚动 .log） | **已实施 ✅**（v7 轮），保留为设计依据 |
| [BLE协议帧规格_开发者版.md](BLE协议帧规格_开发者版.md) | 自研协议 v0.1 帧层实现视角（布局/位图/实例包/状态机） | 草案，自研线帧层权威；冻结二选一见下 |
| [bluetrace_v0.proto](bluetrace_v0.proto) | 自研协议 payload 机器契约 | v0.1 草案；**路径冻结勿挪**（SPEC 引用） |
| [03_collect_protocol_design.md](03_collect_protocol_design.md) + [btcp1_draft.proto](btcp1_draft.proto) | 自研线演进稿 BTCP/1（补离线回传/会话绑定/对时） | 设计稿 v1，评审通过才并入 v0.proto |
| [scenes.json](scenes.json) | 场景词表机器契约（与 app assets 同源） | **路径冻结勿挪勿改** |
| [s7/](s7/) | S7 手表现网协议线（B2A + zqdata），独立 CHANGELOG | 活——共识稿为准，分册五要素齐（2026-07-06 第 22 轮） |
| assets/ | 帧规格示例脚本等 | 随所属文档 |

## 2. 三条协议线的关系（M7 冻结怎么选）

```
                      M7 = 自研 DUT 采集协议解码（唯一硬阻塞）
                                冻结二选一（待固件评审）
                               ┌──────────┴──────────┐
  ① 自研线（本目录）            │                      │   ② UWTP 线（../UWTP/，跨项目标准）
  SPEC §4 + bluetrace_v0.proto ─┘                      └─ UWTP_BLE_Protocol_Design_V0.99
   └ 帧层实现视角：BLE协议帧规格_开发者版                  └ S7 采集 Profile 待办（§22）
   └ 演进稿：03 BTCP/1 + btcp1_draft.proto                └ 前身 UHTP V4（历史勿用）

  ③ S7 现网线（s7/，与 M7 无关、已在跑）：B2A 维护控制台 + zqdata 离线上行
   └ 已落码：S7Console/S7Frame 等（shared.s7）；下一代重设计并入 UWTP 待办
```

- **拍板前纪律**：①② 都不生成代码入库；App 侧解码接入点已就绪（02 架构 R1–R3），冻结后写一个 ProtocolProfile 注册即接入。
- ③ 与冻结无关：现网 S7 手表控制台走 B2A（已实现 15 命令），OTA 传输层可先行（缺口=固件组升级包规范，见 [s7/completeness-audit.md](s7/completeness-audit.md) #8）。

## 3. 变更纪律

- 本目录文档头部必须有**状态行**（草案/已实施/已落码/被取代），状态变化就地勘误。
- 机器契约（`bluetrace_v0.proto` / `scenes.json`）路径冻结；历史文档一律进 [`../归档/`](../归档/)，不在本目录留壳。
