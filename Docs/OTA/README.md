# Docs/OTA · S7 采集固件 OTA 工作线

> 重新构思期（2026-07-07）新增的活跃工作线：在现网 B2A 协议上做"把常规 S7 表刷成采集表"的 OTA。

| 文档 | 内容 |
|---|---|
| [S7采集OTA_设计.md](S7采集OTA_设计.md) + `.html` | 设计与实现计划：定位/真实包坐实/协议逐字节/数据模型/接口/状态机/风险/分阶段/Open Questions |
| [implementation-notes.md](implementation-notes.md) | 活文档：Decisions / Deviations / Edge Cases / Open Questions + 侦察溯源 |

**当前状态**：Phase 0（固件逆向坐实）基本完成；下一步 Phase 1（传输地基 + Mock）。方向 B（实验室 attended），真机可联调。

**协议参考真源**：[`../归档/s7/S7协议共识规格.md`](../归档/s7/S7协议共识规格.md)（B2A + zqdata）。
