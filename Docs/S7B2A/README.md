# S7B2A — S7 手表 B2A 指令协议家族目录

> S7(B2A) 设备指令协议的**唯一入口**（2026-07-14 自 `归档/s7/` 集中迁出）。
> 真源链：固件仓 `E:\1\apollo4_watch_s7`（`b2a_protocol.h` / `ble2appWrap.c`）＞ 本目录共识稿 ＞ App 实现（`shared/src/commonMain/kotlin/io/bluetrace/shared/s7/`）。

## 现行文档

| 文档 | 内容 | 备注 |
| --- | --- | --- |
| [S7协议共识规格.md](S7协议共识规格.md)（[html](S7协议共识规格.html)） | **跨项目共识稿**：B2A + 采集固件 DC/ZQDATA 协议，全字段 file:line 溯源固件代码 | B2A 扩展（M7）的参考真源 |
| [protocol-spec.md](protocol-spec.md) | B2A 协议实现级收敛稿（BlueTrace 侧，按控制台范围裁剪） | AMDTP 传输层 + 帧格式 + 命令 |
| [protocol-b2a.md](protocol-b2a.md)（[html](protocol-b2a.html)） | B2A 下行全命令集逐字节规格（含位域） | |
| [protocol-zqdata.md](protocol-zqdata.md)（[html](protocol-zqdata.html)） | zqdata 上行协议全量审计 | |
| [command-status.md](command-status.md)（[html](command-status.html)） | **指令实现状态清单**（协议全表 vs App 已实现） | 加新指令先查这里 |
| [completeness-audit.md](completeness-audit.md) | 协议文档完整性审计（提取方法与置信度） | |
| [assets/](assets/) | 示例包生成脚本（协议样例可复验实算） | |

## 相关（不在本目录）

- **FILE_TRANS（0x0F）OTA 传输指令**：规格与真机坐实见 [`../OTA/S7采集OTA_指令交互与流程.md`](../OTA/S7采集OTA_指令交互与流程.md)（OTA 任务线目录）
- **s7 协议线历史**：[`../归档/s7/CHANGELOG.md`](../归档/s7/CHANGELOG.md)；UHTP V4 旧稿（已被 UWTP 替代）：[`../归档/s7/protocol-zqdata-uhtp-v1.md`](../归档/s7/protocol-zqdata-uhtp-v1.md)
- **下一代协议（新基线）**：[`../UWTP/`](../UWTP/README.md)（UWTP V0.99+，与 B2A 共存迁移）
- **工作底稿**：[`../归档/s7协议工作底稿/`](../归档/s7协议工作底稿/)（_raw 提取件 + 校验报告）
