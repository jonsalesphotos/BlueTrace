# UWTP v0.2-draft proto/golden 快照（App 侧编解码输入）

> 上游真源：apollo4_watch_s7 仓 `Docs/08_采集V2_离线存储与通信/proto_v02/`（2026-07-15 P1 首版，DRAFT/Registered-Draft）。
> 本目录为**逐字节快照**：`.proto`/`.options` 原样复制；`golden/` 18 个 payload 级样本 + MANIFEST。
> 字段号 v1.0 前可调，调整必须六项同步（工作稿、注册表、App、固件、Golden、修改记录）。

## 与代码的关系

- App 侧编解码 = `shared/src/commonMain/kotlin/io/bluetrace/shared/uwtp/`（**手解 proto3 wire 子集**，
  非 protoc 生成——消息小且少，避免给 KMP 工程引入 protobuf 工具链；字段号/类型以本目录 `.proto` 为准）。
- 对拍测试 = `shared/src/commonTest/kotlin/io/bluetrace/shared/uwtp/UwtpGoldenCodecTest.kt`，
  样本常量 `UwtpGolden.kt` 由脚本从 `golden/*.hex` 逐字节生成（空样本在 .hex 中以 `(空)` 标记）。

## 上游变更时的同步步骤

1. 从上游 proto_v02 重新复制 `.proto`/`.options`/`golden/` 覆盖本目录；
2. 按 `.proto` diff 修改 `UwtpMessages.kt`（字段号/类型/新消息）；
3. 重新生成 `UwtpGolden.kt`（按其头注释说明），跑 `./gradlew :shared:jvmTest` 全绿；
4. CHANGELOG 记一条（六项同步义务的 App 侧两项）。
