# 自研协议线 v0.1（已归档，2026-07-06）

> **归档原因（用户口径修正）**：M7 采集协议路线**不是**"自研 v0.1 vs UWTP 二选一"——
> 正确路线是**在现网 B2A 协议主体上添加/扩展**采集能力；UWTP（`Docs/UWTP/`）只是思考方向，
> 可能借用部分设计（ACK 窗口/断点续传/统一响应等），主体仍是 B2A。
> 本目录的"12B 自研信封 + protobuf payload"整套方案因此整体过时，归档留考古。
> 现网 B2A/zqdata 真源：[`../s7/S7协议共识规格.md`](../s7/S7协议共识规格.md)。

| 文件 | 原角色 |
| --- | --- |
| `bluetrace_v0.proto` | 自研协议 payload 机器契约 v0.1（原 SPEC §4 配套，D-10） |
| `BLE协议帧规格_开发者版.{md,html}` | 12B 帧信封实现视角（布局/位图/实例包/状态机） |
| `03_collect_protocol_design.md` + `btcp1_draft.proto` | BTCP/1 演进稿（离线回传/会话绑定/对时） |
| `gen_frame_examples.py` | 帧规格示例包生成脚本（实算 + 自解码回验） |

注：SPEC §4 内联的帧头/分片/CRC 事实随本次口径修正待重构思（见 SPEC §4 头部注记与
[`../../context/BlueTrace开发_上下文.md`](../../context/BlueTrace开发_上下文.md)）。
