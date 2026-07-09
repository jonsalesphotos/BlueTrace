# Docs/OTA · S7 采集固件 OTA 工作线

> 重新构思期（2026-07-07）新增的活跃工作线：在现网 B2A 协议上做"把常规 S7 表刷成采集表"的 OTA。

| 文档 | 内容 |
|---|---|
| [**S7固件OTA_完整流程与只刷fw失败原因.md**](S7固件OTA_完整流程与只刷fw失败原因.md) + `.html` | **⭐ 固件侧完整流程 + 「为什么不能只刷 fw」代码级证据**：擦空目标区(System.c:3763)→接收→DoRecvAfter Res 硬校验(OTA_Main.c:643-676)→生效门；错误处理/回滚/砖化边界；全 file:line + log5 实证，只 SecBL 闭源处标推测。 |
| [**S7采集OTA_指令交互与流程.md**](S7采集OTA_指令交互与流程.md) + `.html` | **⭐ 权威协议文档（真机 golden 坐实）**：会话时序 + 帧信封 + 五子命令逐字节(真机 hex + 固件 file:line + 逐字段解码) + 累加和 + 完成流程 + 错误码 + App 映射。7 图。 |
| [S7采集OTA_设计.md](S7采集OTA_设计.md) + `.html` | 设计与实现计划：定位/真实包坐实/协议逐字节/数据模型/接口/状态机/风险/分阶段/Open Questions |
| [**S7多设备OTA_设计.md**](S7多设备OTA_设计.md) + `.html` | **⭐ 多设备 OTA 界面与编排设计**：串行批量（一次一台，非并发）+ 顶栏开关（默认关）+ 工作队列（扫描入队不连接/5 态/删除/重试）+ 电量门槛 30% + 失败即跳过重试；复用单设备端到端原语（`OtaProvisioner`/`S7Console`），协议/连接/会话层零改动。4 图 + 数据模型 + 落地计划。**已落码**（CHANGELOG [OTA·多设备]）。 |
| [implementation-notes.md](implementation-notes.md) | 活文档：Decisions / Deviations / Edge Cases / Open Questions + golden 日志验证 + Phase 1.1 修复 |

**当前状态**：传输地基 Phase 1 已落码；Phase 1.1（2026-07-08 两份真机 golden 日志验证 + 修 BUG-1 分片账 + 权威协议文档成文）完成，协议全字节坐实。方向 B（实验室 attended）。**下一步 Phase 2 端到端**（接采集包 + 工具屏 + 完成确认 + 首台真机）。

**协议参考真源**：[`../归档/s7/S7协议共识规格.md`](../归档/s7/S7协议共识规格.md)（B2A + zqdata）。
