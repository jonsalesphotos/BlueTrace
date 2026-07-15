# BLE 库切换评估：Nordic Kotlin-BLE-Library（Android）+ IOS-BLE-Library（iOS）

> 2026-07-14，用户拍板方向：**要 Nordic 双库**（D2 恢复并扩展到 iOS；架构优化笔记 #9 的"续用自写"翻案建议作废）。
> 本文回答"怎么切才不翻车"：库现状核实 → 契约映射 → 真机坑对照必验清单 → 分阶段切换计划（并存+回退）。

## 0. 结论

**可以切，且现在切的时机比 2026-07-06 评估时更好**——W1 通道抽象（进行中）正是两库共同需要的适配面。但必须**分阶段、带回退**：

1. Nordic 作为 `BleClient` 的**第二实现并存引入**（DEBUG 三向开关：自写 / Nordic / Mock），不是替换式切换；
2. 过**真机 A/B 等价闸门**（含 24MB OTA 大包与吞吐基线对比）后 Nordic 转默认；自写实现保留一个版本周期作回退，再按需删除；
3. iOS 二期（M8）用 IOS-BLE-Library 在 Swift 侧实现同一 `BleClient` 契约。
4. W2–W6（识别/分面/固件策略/UI/异构验收）**只依赖 BleClient 接口，不被切换阻塞**，照原计划推进。

## 1. 库现状（2026-07-14 联网核实）

| | Kotlin-BLE-Library | IOS-BLE-Library |
| --- | --- | --- |
| 最新版本 | **2.0.0-beta03（2026-07-02，两周前）** | 0.4.3（2025-10-13） |
| 版本态势 | 2.0 是唯一活跃主线（beta01–03 密集发布：06-19/06-25/07-02）；**1.x 已停更**，新接入没有"稳定 1.x"可选 | 0.x 早期但持续维护（438 commits / 12 releases） |
| API 模型 | Kotlin coroutines + Flow（suspend 函数、Flow 订阅） | CoreBluetooth 的 **Combine/async** 现代封装 |
| 平台 | **Android-only**（无 KMP/iOS——两库是两个平台库，统一层是我们自己的 `BleClient` 接口） | iOS / iPadOS / macOS，Swift 100% |
| 模块结构 | core / client / server / advertiser × `-core`/`-android`/**`-mock`** 全家族分层 | 单库但**真实+Mock 双产品**（SwiftPM 插件同源生成） |
| 测试性 | `client-android-mock` 可做无设备单测（大加分：我们现在 AndroidBleClient 无法单测） | Mock 产品同理 |
| minSdk / 许可 | 21（我们 29 ✓）/ BSD-3 | — / BSD-3 |
| Beta 风险声明 | "Unless some serious issue is found, API should not change"（API 冻结意向，但仍是 beta） | 无 WIP/弃用声明 |
| 2.0 破坏性变更要点 | services 改**事件流**（RemoteServices events 非静态列表）、OperationStatus 改 sealed class、Service Changed 自动订阅、断连行为调整 | — |

## 2. `BleClient` 契约 ↔ Kotlin-BLE 2.0 映射

W1 落成后的 `BleClient` 契约（scan / connect(spec) / disconnect / linkState / negotiatedMtu / notifications(characteristicId) / write(char16)）逐项映射：

| 我方契约 | Nordic 2.0 对应 | 适配层职责 | 风险 |
| --- | --- | --- | --- |
| `scan(): Flow<List<ScannedDevice>>`（累积列表） | scanner 模块 Flow（逐条结果） | 累积去重 + advertisedServices 提取（现逻辑平移） | 低 |
| `connect(device, spec)` | client connect（suspend）+ services 事件流中按 `GattSpec` 定位服务/特征 | services **事件流**消费（2.0 语义）、订阅 spec.notifyChar16s、登记写特征；confirm 二次确认数据源即此 | 中（事件流语义新） |
| `linkState` | 连接状态 Flow | 映射到我们 LinkState 三态 | 低 |
| `negotiatedMtu` | requestMtu / mtu 值 | OTA sliceMax=(MTU−15)×17 依赖，语义不变 | 低 |
| `notifications(characteristicId)` | per-characteristic `getNotifications(): Flow` | 多特征 Flow 合流 + 填 characteristicId（比自写更自然） | 低 |
| `write(deviceId, bytes, char16)` | characteristic write（WRITE_COMMAND/WRITE_REQUEST） | **写流控语义 = PoC 第一验证项**（见 §3） | **高** |
| 连接取消清理 | 结构化并发取消行为 | 验证取消时库是否 close/释放（我们踩过泄漏占死设备坑） | 中 |

## 3. S7 真机坑 × Nordic 必验清单（PoC 闸门，不过不转正）

1. **17 帧切片写流控**（历史坑 BUG-1 变体：无流控背靠背 WriteWithoutResponse 溢出 GATT 缓冲静默丢帧 → 设备 15.36s 看门狗 abort）：验证 Nordic `write(WRITE_COMMAND)` suspend 是否内部排队等写完成回调；若否，适配层保留现有"逐帧串行+等回调+BUSY 退避"流控。**用 24MB ota_all 真包验，对照吞吐基线 ~51.7 KB/s（2026-07-14 D8F7 实测）**——慢太多也算不过（固件看门狗与实验室效率双重约束）。
2. **连接协程取消清理**：取消 connect 后设备须可被再次连接（不留幽灵连接占死设备）。
3. **OTA 复位回连**：设备自复位 → 扫描回连 11s 基线 → 重连读版本（S7 策略语义在上层，但断链事件/重连时序过库）。
4. **MTU 247 协商**与 sliceMax 推算不变。
5. **多特征订阅时序**（B2A FFE2 + 未来多通道）：CCC 串行订阅是否由库妥善处理。
6. **TX/RX 逐帧日志能力**：我们排固件问题靠逐帧 hex 日志（golden log 工作法）——适配层必须保留同等可观测性（库回调点上打日志）。
7. 固件 OTA 门控/61s 看门狗/永不补发 STOP 等语义全在 S7 策略层，与库无关（确认不受影响即可）。

## 4. 风险与缓解

| 风险 | 评级 | 缓解 |
| --- | --- | --- |
| 2.0.0-beta 依赖进生产 | 中 | 版本**锁定 beta03**；并存开关随时回退自写；等 2.0 stable 后再删自写 |
| 写节奏变化影响 OTA 吞吐/固件看门狗 | 高 | §3-1 真机 A/B 硬闸门 |
| services 事件流等 2.0 新语义踩坑 | 中 | 适配层集中消化 + client-android-mock 单测 |
| iOS 库 0.4.x 早期 | 低（二期才用） | M8 时重评版本；接口契约已隔离 |
| 排障可观测性下降（库黑盒） | 中 | 适配层统一日志点（TX/RX/状态迁移）；保留自写实现作对照参考 |

## 5. 切换计划（并入现有波次，任务链已更新）

```
W1  通道抽象契约（进行中，opus）        ← Nordic 路线的前置，不受影响
W1.5 NordicBleClient PoC + 三向开关（opus）：client-android 2.0.0-beta03 实现 BleClient 全契约；
     DEBUG 开关 自写/Nordic/Mock；client-android-mock 适配层单测
W1.6 真机 A/B 等价闸门（主线+用户）：控制台全功能 / 拉日志 / OTA 单+多+循环(24MB) /
     停止善后 / 扫描回连 / HRS 采集；吞吐对照基线 → 过闸 Nordic 转默认
W2–W6 照旧（只依赖 BleClient 接口）
iOS（M8）：Swift 实现 BleClient（IOS-BLE-Library），GattSpec 语义同一份
```

**自写 AndroidBleClient 的归宿**：W1.6 过闸后降级为回退实现保留一个版本周期（开关仍可切回），2.0 stable + 无回退需求后删除。

## 6. 决策记录

- **D2 恢复并扩展（2026-07-14 用户拍板）**：Android=Kotlin-BLE-Library 2.0、iOS=IOS-BLE-Library；架构优化实施笔记 #9 的"续用自写"建议**作废关闭**。
- 统一层 = 自有 `BleClient` + `GattSpec` 契约（W1），**不是**指望 Nordic 提供跨平台——两库是两个平台库。
