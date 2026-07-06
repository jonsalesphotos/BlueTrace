# Docs/legacy —— 归档（仅历史参考）

本目录是 BlueTrace 的**历史文档归档**。当前主线只有两份文件 + 一份机器契约：

- **[`/SPEC.md`](../../SPEC.md)** —— 合并规格（做什么 / 通信协议 / 交互行为 / 数据模型 / 工程口径，自足可执行）
- **[`/Docs/prototypes/v4_android.html`](../prototypes/v4_android.html)** —— UI + 逐屏交互单一真源（37 屏，每屏带 `.screen-ux` 交互块）
- **[`/Docs/architecture/bluetrace_v0.proto`](../architecture/bluetrace_v0.proto)** —— protobuf 机器契约

下列文档的执行口径**已内联进 `/SPEC.md`**，移至此处仅作历史溯源；**任何与 SPEC 冲突处，一律以 SPEC（V4 + 2026-06-17 最新口径）为准**。归档文档内部交叉链接可能失效。

| 文件 | 原角色 | 现已内联于 |
| --- | --- | --- |
| `REQUIREMENTS.md` | 需求条款 + §9 追踪矩阵 | SPEC §2 |
| `BlueTrace_Protocol.md` | 帧 / 分片 / CRC 链路事实 | SPEC §4 |
| `BlueTrace_Architecture.md` | 文件 / manifest / 对齐 / MediaStore / 脚手架 | SPEC §6、§7 |
| `BlueTrace_UI_Implementation.md` | 模块边界 / SessionController / 前台服务 | SPEC §7 |
| `BlueTrace_CrossPlatform_Notes.md` | iOS 分叉 X0–X4 | SPEC §7.5 |
| `BlueTrace_Design_System.md` | 组件 / 配色 / 可达性 | SPEC §8（视觉以原型为准）|
| `BlueTrace_V4_设计契约_2026-06-16.md`、`BlueTrace_V4_决策追踪_2026-06-16.md` | 决策记录 | SPEC §3（D-* / D-V4-*）|
| `BlueTrace_设计审查_2026-06-16.md` | 早期设计审查（10 维）| —（历史）|
| `BlueTrace_PRD.md`、`BlueTrace_UX_Flows.md`、`BlueTrace_UI_Design.md`、`BlueTrace_Architecture_v1_single_device.md` | 更早版本 | —（历史）|
