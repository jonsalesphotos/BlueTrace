# BlueTrace 修改记录（CHANGELOG）

> 个人仓库、直接推 main（不走 PR）。按"构建轮次"组织（对应 `agent_build_prompt_vN.md`）。
> 真源：`/SPEC.md` ＞ `prototypes/v4_android.html`。BLE 默认真实 GATT（2026-07-06 起，DEBUG 可切 Mock）；采集解码走注册式协议架构（02 设计，HRS 已可解；DUT 采集协议 = B2A 主体扩展，方案重新构思中）。高层阶段见 [`里程碑与进度.md`](里程碑与进度.md)。
> 早期 M1–M3 基线细节另见 [`归档/构建笔记/v1_impl_notes.md`](归档/构建笔记/v1_impl_notes.md)、v3 差异见 [`归档/构建笔记/v3_design_diff.md`](归档/构建笔记/v3_design_diff.md)。

---

## [工具链] #24A Kotlin 2.2.10 → 2.4.10 + 删除元数据逃生阀（Nordic 转默认前置之一）— ✅ 2026-07-15
分支 `task/24a-kotlin-2.4`，自基线 tag `baseline-20260715-abstraction-w6` 开出（与 #25 互不依赖，故不叠分支）。**#24 拆两半：本条 = #24A 工具链升级（可独立完成）；#24B = Nordic 默认翻转，硬前置 #25 真机验收，未做。**
- **Kotlin `2.2.10` → `2.4.10`**（2.4 线最新稳定；`2.4.20-Beta1` 是 beta 不取。版本表经 `maven-metadata.xml` 权威核实——Maven Central 的 solrsearch API 返回的版本明显陈旧，不可用）。`kotlin` 版本引用同时驱动 kotlin-multiplatform / kotlin-compose（Compose 编译器插件自 2.0 起与 Kotlin 同版本）/ kotlin-serialization 三插件。
- **删除 `-Xskip-metadata-version-check`**（原在 `app/build.gradle.kts`）——**#24A 的验收点**：删掉逃生阀后 `NordicBleClient` 消费 Nordic beta03（Kotlin 2.4.0 编译）**正常编译通过**，元数据版本正式对齐，逃生阀确认不再需要。
- **兼容面实测全绿**（未动 AGP 9.2.1 / Gradle 9.4.1 / SQLDelight 2.3.2 / Koin 4.0.4 / coroutines 1.10.2 / serialization 1.8.1，均无需跟随升级）：`shared:jvmTest 216/0` + `app:testDebugUnitTest 9/0` + `assembleDebug`（APK 69.9MB），强制重跑非缓存。
- **顺带清理 2 处冗余**（撞风格红线"不写没必要的强制类型转换"）：① [`NordicBleClient.toScanned`](../app/src/main/java/io/bluetrace/data/android/NordicBleClient.kt) 的 `peripheral.identifier.toString()` → `peripheral.identifier`（Android 侧 `identifier` 本就是 `String`，库 core-android `Peripheral.kt:238` `override val identifier: String = impl.address`）；② `DeviceSessionManagerTest` 的 `session.control?.vendor` → `.vendor`（上一行 `is S7DeviceControl` 断言已智能转换为非空）。
- **警告基线对照（实测而非推断）**：`Redundant call of conversion method` 是 **2.4.10 新增诊断**（基线 2.2.10 上不报）；`ExperimentalCoroutinesApi` 未 opt-in（ConnectionRegistryTest）与 `Unnecessary safe call` **基线就有**，非本次升级引入——前者属既有技术债，本轮未动（不扩散 #24A 范围）。

## [BLE·Nordic·抽象层] D2 裁决恢复：Nordic 双库真机闭环 + 设备抽象层 W1–W6 全过闸（判据达成）+ OTA 屏 UI 一轮 — ✅ 2026-07-14/15
- **Codex 独立复核 + 修复一轮（2026-07-15，`5687737` 提交后 GPT 全 diff 复核：0 P0 / 6 P1 / 4 P2，全部核实为真并修复；修后 206 tests/0 failures）**：
  ① **连接入口带 gattSpec**——ConsoleConnect/DeviceScan 连接改 `connect(device, catalog.identify(device)?.gattSpec)`（识别过的协议走声明式通道，未识别保留探测兜底；此前 UI 判"支持"、连接却只认 B2A/HRS 探测，新协议必连不上，Mock 忽略 spec 掩盖了问题）；
  ② **OTA 两屏过滤收紧 PROFILE_S7**——单/多设备 OTA 屏是 S7 专属工具（S7 zip loader/策略/Console），W5 泛化到 `firmwareUpdate != null` 会让异构设备（ZX）入队后被灌 S7 命令；通用 OTA 分派（loader/策略/进度按 profile）另立后续任务；
  ③ **spec 通道全量契约**——两真实 client 的 spec 路径改"声明即必须存在"：缺写特征/缺任一 notify 直接连接失败 teardown（此前静默降级=写丢弃/丢一路数据）；
  ④ **Nordic 多 notify 全就绪**——全部声明特征 CCC 使能才置 CONNECTED、任一路失败整体失败（此前只等首路，W1.5 审查意见①遗留项闭环）；
  ⑤ **OTA 停止运行代际防护**——OtaTestViewModel.stop/MultiOtaController.stopBatch 改"发起时快照 job/strategy/runLog + stopping 门关死重启入口"（此前旧善后跑 app scope 回读可变成员，快速重启会 abort 新策略/关新日志/标错新队列项）；
  ⑥ **控制台附着代际防护**——DeviceConsoleViewModel op/sendDanger 状态回写校验 attachGen（W5 重写时丢了旧实现的 console identity 保护：切 A→B 时 A 的取消 finally 会清 B 的 busy）；
  P2：会话宿主异常安全（controlPlane.create 抛异常不遗留连接/release 断开失败仍 close 控制面）、工程配置越界钳制（lowBatteryPct 0..100/appRetainDays≥0，防关死低电保护）、3 处 runCatching 改 CE 重抛、DeviceLogStore 迁移 LIKE 补尾斜杠+影响行数检查；nit：5 个 app 新文件注释全角→半角机械清理（145 行，字符串文案未动）。
  **未发现回归的维度（Codex 对照红线核验）**：写流控（含对 beta03 jar 的实现核验）、OTA 中止门控/永不 STOP、回连扫描优先+60s 钳制、停止善后 scope。
- **Codex 第二轮复核 + 收口（同日，复核 07fcc32：2 P1 / 3 P2 / 1 nit，全修；207 tests/0 failures）**：
  ① **stopping 门闭环**——MultiOtaController 公开 `stopping` StateFlow，retry/retryAllFailed **改队列前**整体拒绝（此前先把项改 QUEUED 再被拒，用户需再点一次）；VM/UI 透传 stopping（canStart/hasRetriable 禁用），VM startBatch 被编排核拒绝时**回滚刚开的空运行日志**；补「善后阻塞期间 retry/startBatch 拒绝 + 善后完恢复」竞态测试（fake 断开加闸门拉长窗口）；
  ② **Android spec 订阅链"通道可用性"契约**——`setCharacteristicNotification` 返回 false / 特征无 notify|indicate 属性 / 缺 CCC 描述符，任一都判连接失败（此前静默跳过仍 CONNECTED 却收不到数据）；CCC 值按特征属性选 notify/indicate（探测路径行为不变）；
  P2：MultiOta 的 S7 判定统一 `isS7()`（行展示/选择/确认入队三处同口径，堵"状态陈旧或绕过展示层直调"缝）、批循环 disconnect 的 runCatching 改 CE 重抛、pullLog 块内 finally 补代际保护（logRunning 不被旧协程清掉）；nit：两轮触碰的全部 kt 文件注释标点收口（316 行，含防"、→/ 拼出嵌套注释"的防呆），复查残留 0。
- **Codex 三轮复核 + 收口（同日，复核 e40aa9f+9eab2a1：2 P1 / 3 P2；修后 shared 208/0 + app 9/0）**：
  ① **app 级 OTA 操作租约** [`OtaOperationGate`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/OtaOperationGate.kt)（Koin single）——实例内 stopping 门挡不住**跨实例**新一轮（单/多两屏各有编排器、"中止并离开"重进又是新 VM，旧善后跑 app scope 含尽力 RESET+断开最长 10s+）：开始前 `tryAcquire`（CAS）、自然结束或善后**完全结束**后 `release`；两 VM/controller 接入、UI 聚合禁用（模式开关/开始/重试/队列行动作统一 `opBusy` 口径）；补**双 controller 共享租约**竞态测试（A 善后阻塞期间 B 拒绝、善后完 B 可跑、自然结束释放）；
  ② **可刷判定二次演进：UUID → 升级能力工厂类型**——FFE0 只证 GATT 通道面，不证升级编排语义（包格式/FILE_TRANS/门控/同 ID 回连都是策略级契约），同服务但 firmwareUpdate=null/异构策略的未来档案会被误放行；改 `identify()?.firmwareUpdate is S7FirmwareUpdateFactory`（编译期类型符号：既不锚产品名也不误放同服务异构策略）；补 app 单测 4 例（S7 放行/同 FFE0 无升级面拒/同 FFE0 异构策略拒/未识别拒）；
  P2：多设备行级重试按 `opBusy` 置灰（善后窗口不再静默无效点击）、`OtaRunLog.discard()`（删 MediaStore 行，启动被拒时不留假运行日志）、retry 门与 startBatch 前置守卫补租约检查；nit：尾随 `//` 注释标点收口（v3 脚本引号计数排除字符串，411 行）。
- **Codex 四轮终审 + 收口（同日，复核 df9d9fe：2 P1 / 1 P2；修后 shared 213/0 + app 9/0）**：
  ① **租约改 Lease 所有权模型**——布尔开关有两个并发洞：*误释放*（停止恰逢自然结束：运行协程先释放、别处随即 acquire、旧善后的无条件 release 清掉新持有者）与*永久占用*（VM 被导航栈清理/系统回收直接取消协程，finally isActive=false 不释放又无善后接管 → 重进后任何实例永远无法开始）。改 `tryAcquire(): Lease?` + `release(lease)` **CAS 校验 token**（过期释放天然 no-op）；停止时租约**显式移交**善后（不再用 isActive 猜所有权），`onCleared`/`controller.close()` 兜底释放未移交的；补 gate 语义 3 例（含"旧 Lease 延迟释放不清新 Lease"）+ "close 兜底不泄漏"竞态测试；
  ② **retry 原子化**——跨实例租约被占时原实现先把 FAILED 改 QUEUED 再抢租约失败，留"已排队无任务"假状态；重构为 `retryInternal` 原子入口（在跑=只改队列由当前批消费；未跑=**先抢租约再改队列**，改完无可跑项则归还租约），拒绝路径零队列副作用；补"租约被占 retry 队列不变"测试；
  P2：单设备屏 `canReconnect/canDisconnect` 补 stopping/gateBusy（善后期重连会被旧善后再次断开的静默抖动）。
- **Codex 五轮"最后一眼" + 收口（同日，复核 a1951fb：2 P1 / 1 P2；修后 shared 213/0 + app 9/0）**：
  ① **租约槽 CAS 化（释放权唯一化）**——四轮的移交仍有窗口：stop 在"运行主体已完成、finally 未调度"间隙移交时，finally 的 `isActive=true` 会抢先 release（gate 在善后结束前空闲，别处 acquire 后被旧善后断链；token 只能挡 stale release 挡不住这次提前释放）。改 `leaseSlot: MutableStateFlow<Lease?>`：运行协程 finally / stop 善后 / close 善后**谁 CAS 赢得槽谁负责 release**（不再看 isActive），且都发生在对应工作真正结束后——竞态双方必有一败，释放点全局唯一；
  ② **onCleared/close 完整善后**——原先 `cancel()`（非阻塞）后立即释放租约：旧协程仍在飞时新实例可开始并被其断链，且单设备路径无 abort/disconnect（传输态设备挂着等看门狗）。改为与 stop 同款：移交 CAS 赢槽 → app/abort scope `cancelAndJoin → strategy.abort()（门控红线在策略内）→ disconnect → release`；close 测试增强为"清理闸门放行前 gate 保持占用、别处不得 acquire"；
  P2：`canDisconnect` 补 gateBusy（新 VM 不得断开另一实例在用的设备）。
- **Codex 六轮复核 + 收口（同日，复核 38eea22：1 P1；修后 shared 215/0 + app 9/0）**：
  **RunToken 仲裁"善后执行权"**——五轮的槽只仲裁了租约释放权，CAS 败者仍照跑 `cancelAndJoin→abort→disconnect`：两个真实场景 ①"中止并离开"= stop 后紧跟 onCleared/close，双善后并发重复 abort/disconnect（非传输态重复发 CTRL_RESET）②自然结束赢家释放后，陈旧 running 读进来的 stop 照跑善后，打断已开始的新实例。改 `RunToken`（恒非空对象含可空 Lease，消除"无 gate"与"已失所有权"的 null 二义）：运行 finally / stop / onCleared-close 三方**谁 CAS 取走 token 谁才允许执行任何收尾动作**，败者直接退出（onCleared 败者只收自己的日志句柄）。补两条确定性测试：stopBatch 后紧跟 close 断言 CTRL_RESET/断开各仅一次；自然结束后 stopBatch 断言不动设备不动别人租约。
- **Codex 七轮复核 + 收口（同日，复核 68bef3c：1 P1；修后 shared 216/0 + app 9/0）**：
  **finally 的 token 抢占补回 isActive 判定（与 CAS 联用，缺一不可）**——Lifecycle 2.8+ 的销毁顺序是**先取消 viewModelScope（keyed closeable）再调 onCleared()**（AndroidX 源码坐实）：非 stop 销毁路径下，被取消协程的 finally 若无条件 CAS 抢走 token 并释放 Lease，随后 onCleared 的 CAS 必败、abort/disconnect 全跳过——设备无人善后（传输态挂看门狗）而 gate 已空闲。修 `if (isActive && runToken.compareAndSet(token, null))`：isActive 挡"被外部取消"（token 留给接管方做完整善后），CAS 挡"stop/close 已接管"（五轮的移交间隙窗口 isActive=true 时 CAS 必败，不复发）。补按真实框架顺序的测试：先 cancel 运行 scope（断言 finally 不释放 gate）再 close（断言完成断开后才释放）。
- **用户拍板（笔记 #9 关闭）**：Android 主用 **Kotlin-BLE-Library**、iOS 用 **IOS-BLE-Library**——D2 恢复并扩展，架构优化线"续用自写"翻案建议作废。联网核实：Kotlin-BLE 2.0.0-beta03（2026-07-02，1.x 已停更，beta 但 API 冻结意向）；IOS-BLE 0.4.3。**两库是平台库非 KMP**——跨平台统一层 = 自有 `BleClient`+`GattSpec` 契约。切换方案（评估/映射表/必验清单）：[`设计/BLE库切换_Nordic评估.{md,html}`](设计/BLE库切换_Nordic评估.md)——**并存双实现 + DEBUG 三向开关 + 真机 A/B 等价闸门**（W1.5/W1.6 插入波次；重点=17 帧切片写流控语义、连接取消清理、24MB OTA 吞吐对照 ~51.7KB/s 基线），过闸 Nordic 转默认、自写降回退。
- **抽象层 W1（BLE 通道抽象）过闸 ✅（opus 执行 + 主线审查）**：`GattSpec`（服务/多 Notify/写特征/写类型声明式）+ `BleClient.connect(spec)/write(char16)` 默认参数向后兼容；`AndroidBleClient` GattSpec 路径纯加法（CCC **串行订阅状态机**）、真机坑（写流控/取消清理/MTU 时序）逐字未动、通知补填 `characteristicId`（16bit 短码）；Mock 分路（S7 命令面=FFE2/数据面=null）。= 架构优化 P0 特征透传落地。验收 156 tests/0 failures 主线复跑。**审查发现（W2 必修）**：解码侧 profile 通道注册 128bit 全串 vs BLE 层 16bit 短码——`DeviceParserHost.parse` 的按通道分流**从不命中、全靠单通道 single 兜底**掩盖，多通道协议接入前必须统一短码口径。
- 执行模式沉淀：每波 subagent 落码（重构=opus/机械=sonnet）+ 主线审查闸门（diff 审读/验收复跑/对照设计/风格红线），波次状态与审查记录 = [`context/设备指令抽象层_执行笔记.md`](context/设备指令抽象层_执行笔记.md)。
- **W1.5 NordicBleClient PoC 落地 + 真机冒烟（同日）**：`no.nordicsemi.kotlin.ble:client-android/environment-android 2.0.0-beta03`（坐标纠偏：2.0 换了 group）；[`NordicBleClient`](../app/src/main/java/io/bluetrace/data/android/NordicBleClient.kt) 实现 BleClient 全契约（Nordic import 收单文件=隔离面）；**写流控源码级证据**：库内 `BaseRemoteCharacteristic.write` = OperationMutex 串行 + 挂起等 CharacteristicWrite 完成事件（对 WITHOUT_RESPONSE 亦然）——等价自写"Mutex+等 onCharacteristicWrite"，直接映射不叠加；设置页 DEBUG 三向开关（Mock>Nordic>自写，重启生效）。工程级：compileSdk 36→37（Nordic AAR 硬要求）+ `-Xskip-metadata-version-check`（库用 Kotlin 2.4.0 编译，本工程 2.2.10——**转正前置=升 Kotlin ≥2.4 删此逃生阀**）。**真机冒烟（D8F7）**：扫描/连接 2.7s/B2A 探测/订阅/控制台 readAll 全链路 TX/RX 正常（读回 1.1.99.07）；发现必修 bug——`negotiatedMtu` 日志值恒 23，**打回修复并真机复验 ✅**（根因=调用点自伤：日志经 negotiatedMtu 读 conns 表而 Conn 未入表恒回退，协商本身成功；修=peripheralMtu 直接反推+2s 轮询兜底封死理论竞态；复验 `connected ... mtu=247` 与自写一致）。默认路径（自写）零回归（Nordic 类 lazy 不加载）。**W1.5 过闸**。三条审查意见与 A/B 注意事项见执行笔记。
- **W1.6 真机 A/B 两轮（D8F7）**：**首轮（24MB，1.1.99.07→1.1.04）**传输侧过关——552s/42KB/s（基线 51.7 慢 19%，主嫌=逐帧日志开销）、零切片重传、复位断链观测正常（TerminatePeerUser）、**刷写成功**（自写路径复连读回 1.1.04）；但**回连双 bug 卡死打回**：①复位重连后 `services()` 发现 10s 超时——**库源码级根因**=刚复位设备服务表未就绪时库发 `RemoteServices.Failed(EmptyResult)` 快速失败事件，旧代码 `first{Discovered}` 对 Failed 视而不见烧成超时；②`teardown` 的 NonCancellable `disconnect()` 无超时（库内 await 默认 INFINITE）挂死后 60s 预算取消被挡，整链冻结 UI 卡"重连中"。修复=连接前**预注册 services()**（库设计的重连姿势）+ 收敛看齐 `Discovered|Failed` 全终态 + `disconnectBounded`（3s 硬上限）。**第二轮复验通过 ✅（ota_part 19.6MB，1.1.04→1.1.99.06）**：复位断链→扫到广播 11s→**重连 1.8s 一次直达**（mtu=247、服务发现直达 Discovered 无重试）→读回 1.1.99.06 完整闭环。**第三轮吞吐收尾（同日）：A/B 闸门全项收官 ✅**——[`NordicBleClient`](../app/src/main/java/io/bluetrace/data/android/NordicBleClient.kt) 逐帧 TX/RX hex 日志加编译期开关 `FRAME_LOG`（默认关，hex 构造整块门控零残留）；同 24MB 包、同固件起点 1.1.99.07 重测：**Nordic 463s vs 自写 464s 逐秒等价**（稳态 49–52 KB/s；慢 19% 元凶坐实=逐帧日志），回连第三次 1.8s 直达读回 1.1.04。七项记分卡（连接/MTU/命令面/流控/断链/回连/吞吐）全绿；**转默认前置**=Kotlin ≥2.4 升级+删 metadata 逃生阀+默认翻转（任务在册）。设备留 1.1.04；本机 Nordic 开关保持开。
- **抽象层 W2（统一识别）过闸 ✅（opus 执行 + 主线审查，175 tests/0 failures；两轮真机 OTA 扫描/投影全程正常坐实）**：[`DeviceProfile`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/DeviceProfile.kt)（scanSpec/gattSpec/kind + matches 广播匹配 + confirm 连接后二次确认 + 三分面）+ [`DeviceProfileCatalog`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/DeviceProfileCatalog.kt)（identify 识别一次真源 / annotate 打标带"已带身份不覆盖"守卫 / toProtocolRegistry 派生解码注册表）；S7 档案委托 B2aDetect、HRS 挂现成解码、Mock catch-all。**通道键短码统一**（W1 交接必修项闭环）：`DeviceParserHost.channelKey` 两侧 extract16 归一 + HRS/Mock 注册改 16bit 短码（2A37/FEED），5 例路由测试证明按通道分流真正命中（不再靠 single 兜底掩盖）。三 client `toScanned` 去识别化（kind=DUT/profileId=null），识别归 Catalog、VM 投影打标。审查意见（不阻塞）：包环 `protocol.registry→s7`（import B2aDetect）随 W5 B2aDetect 清零把 extract16 迁通用位置。
- **抽象层 W3（控制面分面 + 会话宿主）过闸 ✅（opus 执行 + 主线审查，独立复跑 194 tests/0 failures，新增 19 例）**：[`DeviceControl`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/DeviceControl.kt) 六分面（info/battery/timeSync/logs/power/vendor，**分面 null 即无能力**——无 capabilities 集合、无运行时 Unsupported 窗口）+ `DeviceCommandException`（codeName 在协议实现构造点解析，通用层零 S7 码表）；[`S7DeviceControl`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7DeviceControl.kt) 包装 S7Console **原样不改**（mapS7 异常转换；PowerOps 语义="已发送+尽力确认"，断链=旁证）+ `S7VendorOps`（setDateTime/getPerson/setPerson/findWatch，UI 的 S7 专属块经受限向下转型消费）；[`DeviceSessionManager`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/DeviceSessionManager.kt) app 级会话宿主（acquire=identify→connect **gattSpec 声明式路径首个调用方**→confirm 二次确认→缓存；release=关控制面+断链；W3 只落接口+实现+单测，VM 接线归 W5 故真机行为零变化）。`BleClient.discoveredService16s`（默认 emptyList）三实现落点（Android/Nordic 断链清理齐 + Mock 回 roster）。**Mock 目录裁定 a**：`[S7DeviceProfile, MockDeviceProfile]`——Mock roster 的 S7 设备与真机走同一条识别路径，annotate 守卫 + S7.dataPlane=null 保证扫描/解码零回归（集成测试坐实）。**重复 util 合一**：三处 fmtMB/两处 nowStamp → shared [`Format.kt`](../shared/src/commonMain/kotlin/io/bluetrace/shared/util/Format.kt)（纯 Kotlin HALF_UP 手拼，i18n 审查下沉候选清单首批闭环）。审查修正：KDoc 两处全角顿号改半角。**W4（FirmwareUpdateStrategy + MultiOtaController 队列壳化）同日派发 opus 执行中**——abort 固件门控红线（传输态吞 RESET/永不 STOP/61s 看门狗）整段内迁策略、禁用 PowerOps.reboot 替换。
- **抽象层 W4（固件升级策略）过闸 ✅（2026-07-15，opus 执行至中段被进程退出中断、主线接手收尾；独立复跑 199 tests/0 failures，策略级新增 5 例）**：[`FirmwareUpdate.kt`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/FirmwareUpdate.kt) 通用类型（粗五阶段/进度含 bytesPerSec+detail/终态/`FwPackage` 空 marker + `FirmwareUpdateFactory` 转正——收 device 整对象+abortScope，S7 专属细进度不进通用工厂）；[`S7FirmwareUpdateStrategy`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7FirmwareUpdateStrategy.kt) **纯类型皮+映射层**（OtaProvisioner/S7OtaSession/OtaAbort 编排零改动整链内迁；**abort 红线**=传输态 otaTransferActive 内部自持、传输中吞 RESET/永不 STOP/61s 看门狗、禁 PowerOps.reboot 替换；细 OtaPhase→粗阶段映射+细文案塞 detail）；`OtaPackage : FwPackage`；`S7DeviceProfile.firmwareUpdate` 挂工厂；MultiOtaController **队列壳化**（逐台 strategy.run、stopBatch 转发 strategy.abort、电量门槛保留短命 S7Console）；单设备 OtaTestViewModel 同步下沉策略——**策略=app 层唯一编排入口**（OtaProvisioner/S7OtaSession/OtaAbort 的 app import 清零）。策略级测试：成功链+粗映射+detail 透传 / FwPackage 类型不符 fail fast / **传输中 abort 不发 RESET（帧捕获）** / 非传输态发 / 未连接跳过。
- **OTA 屏 UI 一轮（用户 5 条，2026-07-15 随 W4 收尾落地，真机 D8F7 冒烟过）**：①目标设备卡就地「断开/重连」chip（仿 DUT 控制台头卡，消费点击不进连接页）②删「当前版本」行+进屏自动读版本 ③版本移进度卡「版本 A → B」（**开始 OTA 前读一次+回连读一次**=仅有的两处读点）④运行自然结束**自动断开**（循环=整批结束才断）⑤各轮结果行加**本轮下载平均速度**（VM 层 onOtaPhase 计时结算，零 shared 改动）。
- **抽象层 W5（UI 分面驱动 + B2aDetect 清零）过闸 ✅（2026-07-15，opus 执行 + 主线审查真机回归；独立复跑 200 tests/0 failures）**：`extract16` 迁通用 [`ble/Uuid16.kt`](../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/Uuid16.kt)，**B2aDetect 引用 S7 包外清零 + 包环 protocol.registry→s7 消除**；[`DeviceConsoleViewModel`](../app/src/main/java/io/bluetrace/viewmodel/DeviceConsoleViewModel.kt) 重写为 **DeviceSessionManager 首个运行时消费方**——控制台按六分面 null 显隐功能块、S7 专属块经 `vendor as? S7VendorOps`（扩展 13 法全 S7Console 薄委托，工程控制台零行为变化）、失败统一 DeviceCommandException（S7 异常从 UI 清零）；identity 三口径精化（可控=controlPlane/可 OTA=firmwareUpdate/采集排除=controlPlane）。真机（D8F7）：分面控制台全链读回 ✓、断开黏附/重连 chip ✓、**gattSpec 声明式路径首次真机触发**（spec=FFE0 物理建链+MTU ✓；发现段被下条库问题所阻，补验挂靠）。**W6（异构 Mock 验收）就绪**：缺分面自动隐块 + 新协议=只加包+一行注册的框架已具备。
- **Nordic「主动断开→重连」服务发现挂死：库级问题定性 + 缓解落地（2026-07-15）**：主动断开后同进程重连——物理建链成功但服务发现挂 Discovering 超时、teardown 断开亦挂、重试必挂、反复后污染系统栈（恢复=**开关蓝牙**>杀 App）；probe/spec 都踩、force-stop 后首连秒过。嫌疑=W1.5 备案的**库内全局 OperationMutex 卡死**（断开时取消订阅触发 CCC disable 写与断链竞态挂锁，后续一切 GATT 操作排队永不执行）。缓解：teardown/disconnect 断开 fire-and-forget（UI 不冻结可重试，实证生效）+ 断开序改"先物理断链后取消订阅域"（[`DeviceSessionManager.release`](../shared/src/commonMain/kotlin/io/bluetrace/shared/device/DeviceSessionManager.kt) 换序）——未根治，深挖（库源码/报 issue）单独立项并列入 **Nordic 转默认前置**。
- **抽象层 W6（异构第二协议验收）过闸 ✅ = 总验收判据达成，六波闭环（2026-07-15，sonnet 执行 + 主线审查；独立复跑 205 tests/0 failures）**：新增 ZX 假协议包 [`shared/.../zx/`](../shared/src/commonMain/kotlin/io/bluetrace/shared/zx/ZxDeviceProfile.kt)——与 S7 **全维度异构**（服务 AA00/写 AA01 With-Response/通知 AA02；matches/confirm 用框架默认实现反证识别面协议中立；**缺 logs+vendor 分面**→控制台对应块自动隐藏；升级策略**"原地生效"零断链零回连**→"自复位回连"证明是 S7 私有假设非框架假设）。触碰面独立核实=**仅 catalog 一行插入 + Mock roster 一条数据**，框架（device/protocol.registry/s7/双 BLE client/app VM/UI）零改动。**设计 V2 验收口径成立：新增一种协议设备 = 一个协议包 + 一行注册**——项目初衷"兼容其他指令的设备"自此有可执行证明。
- **NordicBleClient 连接页卡死 bug 修复（W1.6 补丁，2026-07-15 真机撞出）**：连接被取消后底层 GATT 脏 → 重连服务发现挂 Discovering 超时 → teardown 里 `withTimeoutOrNull(3s)` **切不掉库 disconnect() 内部清理挂起**（body 不结束超时不返回，"timed out"日志都打不出）→ connect() 永不返回 → 连接页 busy 永挂冻结"连接中"（实证 2 分钟不恢复）。**修**=teardown/disconnect() 底层断开改 **fire-and-forget**（丢 app 级 scope，保留 3s 限时日志）：conns/link 收尾无条件立即到达、调用方立刻可重试。真机复验断开↔重连快速切换零冻结。

## [固件审查·抽象层·文档] OTA 中止固件行为坐实 + 停止策略修正 + B2A 文档集中 + 指令抽象层设计 + i18n/KMP 审查 — ✅ 2026-07-14
用户四项指令（同日第四轮）：固件代码审查（不猜）/B2A 文档独立目录/设备指令抽象层/i18n+KMP 审查。`:shared:jvmTest` + `:app:assembleDebug` 绿，APK 已装机。
- **固件审查（apollo4_watch_s7，9 代理并行 + 人工收敛）**：**"已发送重启指令但没真重启"= 固件根本没处理，用户判断正确**——`ble2appWrap.c:12021` OTA 门控在命令 switch 之前把一切非 FILE_TRANS(0x0F) 命令**无条件丢弃**（不回包/不排队），重启指令到不了 `SYS_Reset()`(:13147)；真正的复位途径 = 收包空闲**看门狗 ≈61s**（60×1024ms）超时自复位，且 `SYS_Reset` 复位前 `AppConnClose()` **主动断链**（推翻 App 侧此前"硬复位不发断链包/观测滞后"归因，CHANGELOG 上条已勘误）。**致命陷阱立此存照**：中止时若补发 `FILE_TRANS_STOP`，固件删看门狗定时器且**不清 OTA 标志** → 设备永久卡传输态（不复位不断链）——App 取消路径天然不发 STOP，`S7OtaSession` 类注释 + 审查文档双处存照。全部 file:line 证据：[`OTA/OTA中止_固件行为审查.md`](OTA/OTA中止_固件行为审查.md)（含固件侧改进建议：门控放行电源类 key / StopAck 补清标志）。
- **停止策略按证据修正**：[`OtaAbort`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaAbort.kt) 增 `otaTransferActive` 短路——**传输中（Downloading）不发重启指令**（发也被吞），直接断开本地连接，日志/UI 如实提示"设备约 60s 后由固件看门狗自动重启"；非传输态（未 REQ / 复位重连后）照发且即刻生效。两停止路径（单设备 `stop()` / `MultiOtaController.stopBatch`）按 phase 分支；测试改判：`stopBatch_midTransfer…skipsReset`（帧捕获断言**不发**）+ 新增 `stopBatch_beforeTransfer_sendsReset`（READING 阶段照发）。
- **B2A 协议文档集中**：新建 [`S7B2A/`](S7B2A/README.md) 家族目录（对齐 UWTP 先例），git mv 迁入共识规格/protocol-spec/protocol-b2a/protocol-zqdata/command-status/completeness-audit/assets（共 11 件）；`归档/s7/` 只留 s7 线 CHANGELOG 与 UHTP 旧稿并加 README 指针；全库 22 处引用链接批量修复，S7B2A 死链清零；`Docs/README.md` 目录地图与导航表更新。
- **设备抽象层设计 V1→V2（同日两稿）**：V1（DeviceCommandProfile 指令适配层）当日被深度评审推翻——五条硬伤：BLE 通道抽象缺位（AndroidBleClient 硬编码 FFE0/FFE1/FFE2/单写特征）、双 registry 重复识别、OTA 模型带 S7 语义上移（moduleId/BFTT/REQ-START-STOP）、capabilities 大接口+向下转型矛盾、会话无应用级宿主。**V2 全盘采纳**（[`设计/设备指令抽象层_设计.{md,html}`](设计/设备指令抽象层_设计.md)，修订对照表 §7）：`DeviceProfile` 统一识别一次（Catalog+confirm 二次确认）→ 三分面工厂（DataPlane=现解码 profile 挂入 / ControlPlane=六分面+vendor，null 即无能力 / FirmwareUpdate=**整体策略**吸收 OtaProvisioner，传输/复位/回连全在协议实现内）→ 通道感知 `BleConnection`（GattSpec 声明式，= 架构优化 P0 合流）；`DeviceSessionManager` app 级宿主。波次 4→6 重排（W1 BLE 通道 → W2 Catalog → W3 分面 → W4 FW 策略 → W5 UI → W6 **异构** Mock 验收：不同服务/缺一能力/不同重连行为，判据=只新增协议包+一行注册）。执行模式=每波 subagent 落码（重构波 opus/机械波 sonnet）+ 主线审查闸门，状态见 [`context/设备指令抽象层_执行笔记.md`](context/设备指令抽象层_执行笔记.md)；V1 版 W1 agent 已中止且工作区无残留。
- **i18n + KMP 审查**：[`代码审查报告_20260714.md`](代码审查报告_20260714.md)。i18n 总体健康——正式链路零硬编码、strings.xml zh/en 383 键双向零缺失；**唯一真泄漏 `Collect.kt` MAG("地磁")/TEMP("温度") 已修**（label 改英文 token）；豁免区=DEBUG OTA 工具屏(B)+shared 工程日志(C)。KMP——commonMain **零违规 import、零 expect/actual**（接口注入模式）；但 shared 无 iOS target（纸面能力），结构性障碍表（MediaStoreExporter/DeviceLogStore/ConfigStore 等无接口）+ 下沉候选清单（fmtMB×3/nowStamp×2/summaryLine 重复、设备行投影）入册，节奏=M8 前专门一波。

## [存储·OTA] v8 目录树重组（log/config/rawdata）+ OTA 三硬化：扫描回连≥60s / 停止发重启 / 错误定位 + 执行日志落盘 — ✅ 2026-07-14
调试信息落盘体系 + OTA 可靠性一轮（用户需求 2026-07-14）。`:shared:jvmTest` + `:app:assembleDebug` + `:app:testDebugUnitTest` 绿。**目录名恒英文**（i18n 红线）。
- **公共目录树重组**（[`PublicDownloadStore.kt`](../app/src/main/java/io/bluetrace/data/android/PublicDownloadStore.kt) 定树）：`Download/BlueTrace/` 下 **`log/`主文件夹**（`ota/` OTA 执行日志、`firmware/` 设备固件日志、`app/` 应用日志）+ **`config/`**（工程配置 JSON 镜像）+ **`rawdata/<YYYY-MM-DD>/`**（会话导出按导出日期归档）。迁移链：旧公共 `logs/` 的 `s7_devlog*` → `log/firmware/`（MediaStore 改 RELATIVE_PATH 原地移，ROM 不支持则复制+删除回退，[`DeviceLogStore`](../app/src/main/java/io/bluetrace/data/android/DeviceLogStore.kt) 首次 list 幂等跑）；私有 `files/logs/` → `files/log/app/`（[`appLogsDir`](../app/src/main/java/io/bluetrace/data/android/AndroidPlatform.kt) 启动迁移）。
- **工程配置 JSON**：shared [`EngineeringConfig`](../shared/src/commonMain/kotlin/io/bluetrace/shared/config/EngineeringConfig.kt)（kotlinx.serialization，lenient+ignoreUnknown+encodeDefaults；字段 `export.rawdataByDate` / `ota.reconnectScanSeconds`（**下限 60 钳制**）/ `ota.lowBatteryPct` / `log.appRetainDays`）。[`ConfigStore`](../app/src/main/java/io/bluetrace/data/android/ConfigStore.kt)：**真源 = 私有 `files/config/bluetrace_config.json`**（缺失写默认完整清单；坏 JSON 保留原文件、内存回默认），公共 `config/` 只放**只读镜像**——不回读公共侧（MTP/他 App 改写会换 MediaStore owner 致失读）。改参路径：adb push 私有文件重启生效。
- **OTA 执行日志落盘**：[`OtaRunLogStore`](../app/src/main/java/io/bluetrace/data/android/OtaRunLogStore.kt) 每次运行一个 `log/ota/ota_{single|loop|multi}_<ts>.log`，Channel+单线程写协程**逐行 append+flush**——循环 OTA 数小时全量历史不受终端 300 行内存窗限制，进程中途被杀最多丢一行；octet-stream 防 MediaStore 强补 `.txt`。两 VM 起始打印落盘路径与扫描预算/电量门槛。
- **应用日志公共镜像**：[`AppLogMirror`](../app/src/main/java/io/bluetrace/data/android/AppLogMirror.kt) 启动后台把私有滚动日志 upsert 到 `log/app/`——崩溃后**重开 App 即可用文件管理器拿到含崩溃栈的日志**，免 adb；设置页导出/控制台操作日志导出同落 `log/app/`（exporter 改 octet-stream，`.log` 名保真）。
- **OTA 回连 = 扫描优先 ≥60s**（产品硬门）：[`OtaProvisioner.reconnect`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt) 重写——`withTimeoutOrNull(预算)` 内 `scan().firstOrNull{目标广播}` **扫到才连**，连不上回到扫描等下一次广播；扫描流不可用/预算耗尽 → 直连重试兜底（老路径保留）。新增 `OtaPhase.Scanning`（两屏标签「扫描回连」）。预算走配置只可调大，测试用虚拟时钟断言 elapsed≥60s。
- **手动停止 → 直接发送重启指令**（产品要求）：新增 [`OtaAbort.rebootAfterManualStop`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaAbort.kt)（CTRL_RESET 复用 `sendPowerCommand`，不回包、断链=生效）；单设备 `stop()`/多设备 `stopBatch()` 均 `cancelAndJoin` → 发指令 → 收尾关日志。**善后跑 app 级 abortScope**——「中止并离开」随即销毁 VM，viewModelScope 上发不出去（真坑）。多设备当前台标「手动停止」（可重试）；退出确认弹窗文案注明会发重启指令。
- **错误定位展示**（循环 OTA 到哪个阶段/哪条指令/传输错在哪）：[`OtaFailure.describe()`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaPackage.kt) 结构化中文——超时/设备错误带**出错指令**（REQ/START:file/STOP:file），切片失败带**文件名+字节偏移**，REQ 拒绝带原因名（DISK_FULL/BUSY/MEMORY），设备错误码查 `S7.errorName`。单设备屏新增**失败详情卡**（轮次+指令+中断位置=文件 i/N·已发/总字节）；各轮结果与多设备队列行失败原因全部走 describe。
- **导出按日期**：`MediaStoreExporter.exportSession` → `rawdata/<ISO 日期>/`（`export.rawdataByDate=false` 退平铺）；设置「导出位置」、导出说明、console 拉日志说明中英资源同步更新。
- **测试**：新增 [`EngineeringConfigTest`](../shared/src/commonTest/kotlin/io/bluetrace/shared/config/EngineeringConfigTest.kt)（默认/部分 JSON/垃圾/60s 钳制/往返）6 例；provisioner 扫描回连 3 例（扫到才连 / 预算≥60s / 首连失败回扫描）；controller 手动停止 1 例（**帧捕获断言 CTRL_RESET 真发出**）。既有 provisioner/controller 用例语义不变（emptyFlow 扫描流走直连兜底=老路径）。测试坑：kotlinx-coroutines-test 的 `testScheduler.advanceUntilIdle()` **不跑 backgroundScope 任务**（只推进到"无前台任务"即返回）——后台编排断言须像本文件其他用例一样用挂起等待（`first{}`/`join()`）驱动。
- **扫描添加表易用性（同日两轮，用户反馈"第一屏看不到 RSSI 最大的设备"+ 抽屉易误关/页面乱跳）**：全量显示不做折叠（首轮做的"近处优先 RSSI 门槛 + 显示全部切换"被用户否掉——页面不加说明性文字，默认即全部）；靠**排序**保证打开即见手边的表：supported 在上 → **RSSI 5dBm 分桶**降序 → 名称 tiebreak（1s 采样抖动不再让行序每秒乱跳）。**滚动锚点按页面位置不追设备**：LazyColumn 去掉 `key`（有 key 时 Compose 跟随首个可见 item 的设备漂移，扫描重排会带着视口乱跳；按 index/offset 记就是纯"页面距离"）。**列表滚动不再拖关抽屉**：LazyColumn 挂 `nestedScroll` 连接吃掉列表消化不掉的滚动/惯性，不上抛给 ModalBottomSheet——列表里下滑只滚列表，关抽屉走顶部横条下拉/点遮罩/返回键。真机复验：列表内连续下滑抽屉不动、横条下拉正常关、第一屏即手边最强的表（D8F7 -36dBm 在顶）。
- **真表 D8F7 OTA 实测 + 日志/速度增强（同日第三轮，用户边测边提）**：真表 `OTA_Overseas_V1.1.04.01`（24MB/14 文件）**464s 传完（均速 ~51.7 KB/s）→ 复位断链 → 扫描回连 11s 命中 → 读回版本 1.1.04.01**（readVersion 首超时被容错重试救回）——扫描回连/停止发重启两项真表待验项全过。实测发现：手动停止后「10s 未观测到断链」→ 当时归因"硬复位不发断链包、supervision timeout 观测滞后"并放宽观测窗到 20s + 单设备停止善后补 `disconnect`+退册——**该归因当日稍后被固件代码审查推翻（传输中重启指令被固件 OTA 门控整个丢弃，见 [固件审查] 条），勘误于此**。**日志增强**：落盘执行日志行首改**完整本机时间** `yyyy-MM-dd HH:mm:ss.SSS`（屏幕终端保持 `[HHMMSS]`）；**逐切片 TRANS 行**（文件内偏移 · 总进度% · 瞬时速度，约定 TRANS 前缀只落盘不刷终端——ResData 级 4500+ 切片会冲掉终端 300 行窗）；失败行带**已传字节+百分比**；DONE 行带**用时+平均速度**。**BLE 上传速度显示**：`OtaProgress.bytesPerSec`（S7OtaSession ≥1s 滑动窗口），单设备进度卡 + 多设备队列行 Downloading 阶段显示。**logs/ 与 log/ 合并**：旧公共 `logs/` 迁移扩展为**整目录分流**（s7_devlog*→`log/firmware/`，其余→`log/app/`），Application 启动后台自动跑（不必进日志列表页），搬空尽力删空目录；真机验证 s7_oplog 已分流、唯一遗孤 `bluetrace_log.txt`（旧安装 owner，App 无权迁）adb 手工移完，`logs/` 目录已消失。
- **真机验证 ✅（2026-07-14，M2101K9C，Mock 后端走通全链）**：启动树创建+config 默认清单+公共镜像 ✓；私有 `files/logs`→`log/app` 迁移 ✓；**29 个存量 s7_devlog 全量迁移** `logs/`→`log/firmware/`（MediaStore 改 RELATIVE_PATH 原地移动成功，非 devlog 两文件按设计留守）✓；多设备 OTA 批量 2 台跑通，`log/ota/ota_multi_*.log` 逐行落盘（配置头/传输/扫描回连/汇总全程）✓；队列行「扫描回连」阶段与日志「预算 60s，等目标广播出现…→已扫到设备广播(第 1 次)→连接」✓；rawdata 按日期导出 `rawdata/2026-07-14/<会话>.zip` ✓。**真机发现并修复 1 个时序 bug**：viewModelScope=Main.immediate 下 `controller.startBatch` 内协程立即跑到首个挂起点、头两行日志经 collector 同步镜像时落盘文件还没打开 → 丢「===== 开始批量升级 =====」头——修复=先开文件再 startBatch（同前置守卫），复验头部完整。未覆盖（待真表长传输场景）：停止时 CTRL_RESET 实发（jvm 帧级断言已覆盖；Mock 单台全程 ~5s，UI 停止窗口太小）、真实射频 60s 扫描回连、失败详情卡实景。

---

## [OTA·多设备] 多设备 OTA：顶栏开关 + 工作队列串行批量 + 电量门槛 + 失败重试 — ✅ 2026-07-09
按讨论先出设计文档 [`OTA/S7多设备OTA_设计.{md,html}`](OTA/S7多设备OTA_设计.html)（4 图：每台状态机/界面线框/状态词汇/串行时序）后开工。`app:compileDebugKotlin` 绿、`shared:jvmTest` s7 全绿。**协议/连接/OTA 会话层零改动**，只加编排 VM + 多设备 UI。
- **顶栏「多设备」开关（默认关）**同屏切两态：关=单设备现状（1/2 包 A→B 循环）不变；开=工作队列批量。开关运行中锁定；放进 [`BtTopBar`](../app/src/main/java/io/bluetrace/ui/components/Common.kt) 现成 `actions` 槽。
- **串行（一次一台，非并发）**：设备端 REQ 需人工授权 + 切片看门狗贴 ~15s + 刷完重连风暴 → 串行是唯一安全解；UI 呈现为批量。新增 [`viewmodel/MultiOtaViewModel.kt`](../app/src/main/java/io/bluetrace/viewmodel/MultiOtaViewModel.kt)（队列 + 串行遍历 + 扫描入队 + per-device 状态聚合）。
- **每台流程复用现成件**：`ble.connect`+入册 → `S7Console.getDeviceInfo().swVer`/`getBattery().percent` → `OtaProvisioner.provisionAndReconnect`（刷写+等复位+重连+读版本）→ 复读电量 → `ble.disconnect`+退册。
- **扫描添加＝只入队、不连接**；仅 B2A/S7（`B2aDetect.matchesAdvertisement`）可加，其余灰掉标「不支持 OTA」；底部 `ModalBottomSheet`。
- **电量门槛 30%**：刷前 <30% 跳过（固件无低电门控，掉电变砖由 App 兜底）。
- **失败即跳过 + 手动重试**：任一步失败标 `FAILED`/低电 `SKIPPED`、继续下一台；失败/跳过行显重试图标，可单台/「重试全部失败」。
- **增删规则**：开始后禁新增；「待升级」及已完成/失败/跳过行可删，**通信中的当前台锁定**；停止批量/返回走退出确认弹窗（单/多两态通用）。
- 新增 [`ui/screen/settings/MultiOtaScreen.kt`](../app/src/main/java/io/bluetrace/ui/screen/settings/MultiOtaScreen.kt)（`MultiOtaBody` + 队列行 5 态 + 扫描表）；`OtaTestScreen` 顶栏加开关并按 `multiMode` 分支；DI 注册 `MultiOtaViewModel`。
- **编排核下沉 shared + 自动化覆盖**（同会话追加，approach 2）：串行编排核从 app VM 抽到 shared [`MultiOtaController`](../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/MultiOtaController.kt)（commonMain 无 Android 依赖、iOS 可复用，`DeviceOtaStatus`/`DeviceOtaItem` 一并下沉），`MultiOtaViewModel` 改**薄壳**（包加载/扫描/日志镜像/UI 状态组合）——与仓库惯例一致（重活在 shared、VM 薄）。新增 jvmTest [`MultiOtaControllerTest`](../shared/src/commonTest/kotlin/io/bluetrace/shared/s7/MultiOtaControllerTest.kt)：N 个 `S7MockWatch` 造多设备假 `BleClient`，覆盖 串行全完成 / 失败即跳过继续 / 电量门槛跳过 / 单台重试，**4 用例绿**。
- **验证**：`:app:compileDebugKotlin` 绿；`:shared:jvmTest` s7 全套（含新 4 例 + 既有 provisioner/console/mock 回归）绿。
- **Mock 多台 + 真机装机验证**：[`MockBleClient`](../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/mock/MockBleClient.kt) 从 1 台 S7 扩到 3 台（每设备独立 `S7MockWatch` + inbound，roster 加 S7-A31B/S7-2D90）；多设备屏加 DEBUG 示例包入口（长按「添加烧录包」载入内存合成包，**仅 Mock 模式可用**——真实 GATT 下 no-op，防误刷垃圾包）。真机 M2101K9C 走通端到端：开 Mock BLE → 多设备 → 扫描（S7「可加」/ 非 B2A「不支持 OTA」）→ 选 2 台入队 → 载入示例包 → 开始批量 → **串行逐台**（设备1 刷写时设备2「待升级」）→ 两台各 连接·读版本电量·刷写·重连·复读 → 汇总「完成 2」，日志 REQ/START/STOP/DONE 全链印证。
- **两处 UI 修复（真机反馈）**：(1) 扫描添加底部弹窗默认半展开导致「加入队列」按钮要上拉才可见 → `rememberModalBottomSheetState(skipPartiallyExpanded = true)` 直接全展开；(2) 队列行「版本 X→Y · 电量 A%→B%」原塞在 `weight(1f)` 列被右侧药丸/删除键挤占截断 → 移到名字下方整行铺满（左缩进 34dp 对齐）。另：Mock 的 S7 表默认 `otaRebootAfterComplete=true`（刷后模拟自复位断链→重连闭环，演示更真、无 90s 空等）；DEBUG 示例包入口加「仅 Mock 模式可用」安全门（真实 GATT no-op，防误刷垃圾包）。真机复验两处修复 OK。

---

## [OTA·UI] OTA 固件屏交互收尾：整卡可点进扫描 + 固定不跳 + 运行护栏 + 扫描 RSSI 统一 -48 — ✅ 2026-07-09
本次会话多轮打磨，`app:installDebug` 绿、真机 M2101K9C 装机验证。
- **信息卡整卡可点 → 进扫描/连接页**（`ConsoleConnect`），唯一例外=「当前版本」值 chip（嵌套点击=刷新版本）；去掉「重连」chip（重连改在扫描页做）。
- **固定列宽/行高、运行态不跳动**：标签列 80dp、两行行高 28dp、连接状态恒为 `StatusPill`、chevron `›` 常驻——消除「开始 OTA 后卡片跳一下」。
- **OTA 运行中护栏**：禁止进扫描页（卡片不可点、chevron 变暗）；禁止返回——系统返回键/顶栏箭头 → **退出确认弹窗**（继续刷写 / 中止并离开，后者停 OTA 再返回），逃生口=停止按钮；副标题变「刷写中 · 请勿退出」。
- **扫描 RSSI 阈值默认全局统一**：新增 [`viewmodel/ScanDefaults.kt`](../app/src/main/java/io/bluetrace/viewmodel/ScanDefaults.kt) `RSSI_THRESHOLD = -48`，两连接页 VM（`DeviceScanViewModel` 原 -80 / `ConsoleConnectViewModel` 原 -90）统一引用；-48 偏强只显眼前手表（多表环境好用），远设备被滤则滑条往 -99 调低。

---

## [OTA·Phase 2f]「OTA 固件」屏重构：动态包列表 + 终端日志 + 重连 + 自动版本 — ✅ 2026-07-08
按用户布局反馈重构（真机截图验证、无崩溃）。`app:assembleDebug` 绿，APK 已装机。兼容正式版固件、不改固件。
- **改名** OTA 测试 → **OTA 固件**（标题 + 设置首页入口）。
- **固定信息框**：目标设备 + 当前版本两行；**连上自动读版本**（linkState→CONNECTED 触发）；**断联可重连**（「重连」chip，仿 DUT），设备黏性（断联保留、可重连）。
- **动态包列表 + 「+」上传框**：虚线「+ 添加烧录包」，最多 2 个、可重复添加同一个；去掉 A/B 字样（列表叫「烧录包 1/2」）+ 去掉循环次数/toggle。**1 包=单次 OTA；2 包=包1→包2 循环升级（重复到手动中断）**。
- **开始 OTA**：运行时变「停止」(单次)/「中断重复测试」(循环，红)。
- **选包/校验/连接/版本 信息统一进「执行日志」**（终端风：黑底等宽 + 颜色分级 + 清空按钮）；包卡片只显 📁+文件名+✕。
- VM 改 `packages: List<OtaPkgItem>`（≤2）+ `loadedPkgs` 持字节；`addPackage/removePackage/reconnect/autoReadVersion`；1/2 包决定单次/循环。
- **答用户问**：不改固件下**最小 OTA 包 = 4 文件（ota_part：fw+ResData/ResFat/ResCheck）**，非 14——字库固件 Step2 自动补拷，Res 必推。`ota_part.zip` 已在手机 `/sdcard/Download/`。

---

## [OTA·文档] 固件端 OTA 完整流程 + 「为什么不能只刷 fw」代码级证据成文 — ✅ 2026-07-08
应用户要求出一份**代码级证据**的固件侧 OTA 文档，钉死了此前 O-1 分析的最后缺口——**目标区在哪一行被擦空**。产出 [`OTA/S7固件OTA_完整流程与只刷fw失败原因.{md,html}`](OTA/S7固件OTA_完整流程与只刷fw失败原因.html)（2 图，全 file:line + log5 实证）。
- **锁定擦除点**：`SYS_OtaStart(2)`（`System.c:3706`）里 **`:3763 SYS_fremove_all(目标资源区)`** —— OTA 一开始就擦空目标区（`SYS_flist_all` 是 `f_findfirst` 真目录迭代，log5 擦前 14 文件/擦后空 逐条印证）。之前 workflow 只查了 DoRecvBefore（空壳）漏了这里。
- **完整链**：擦空(System.c:3763) → 只写推送文件(ble2appWrap.c:4850) → DoRecvBefore 空壳不补拷(OTA_Main.c:222) → **DoRecvAfter Step1 硬校验 Res 三件套存在(OTA_Main.c:643-676，缺→EECT_OTA_LOAD_RES 1101)** → 生效门 `if(EECT_OK) SetFlag+Done`(:1883-1888)。**字库例外**：Step2 缺则从旧区 `SYS_CopyFile` 补拷(:699)——故 ota_part 可行、fw-only 不可。
- 错误处理总表(EBEC/尺寸兜底/回滚 OTA_Check ErrCnt→SecBL/砖化边界)；真 O-1 唯一正解=固件在 DoRecvAfter Step1 给 Res 加"缺则补拷"(镜像 Step2)。SecBL 闭源处已标【推测】，核心论证不依赖它。

---

## [OTA·Phase 2e] O-1（只推 fw.dat）实测不可行 + 校验器放开(带实况警告) — ✅ 2026-07-08
workflow 3 路 + 亲验 `OTA_Main.c:192/195/222` 静态分析 → 首验(log5.txt)**推翻"可行"判断**。`shared:jvmTest`(ValidatorTest 10) + `assembleDebug` 绿，APK 已装机。
- **静态分析(对了一半)**：编译版 `OTA_DoRecvBefore`(:197) 空壳(:222 无条件 return；:788 拷 Res 版死码)、**lData 全量/差分设备侧无效**——但漏了"目标区不保留 Res"。
- **实测(log5.txt)推翻**：fw.dat 1.67MB **~30s** 传完 ✅，但目标区 A **OTA 前有完整 Res、OTA 后只剩 fw.dat**（Res 未保留/不补拷）→ `DoRecvAfter Step1 Missing 0:/A/ResCheck.dat`→`lRet:1101`→**末 STOP code=13(FSUM_FAIL)**→ App 正确显失败。**非砖**(ucOtaFlag:0)。擦除机制待固件侧定位。
- **可行路径**：① ota_part(fw+Res 去字库)~8min 稳；② 真 O-1(~1min) 需固件改 DoRecvAfter Step1 缺 Res 补拷(镜像 Step2 字库)。
- **校验器放开**：`OtaPackageValidator` `RES_FILES`/`FW_FILE` 分离，不硬求 Res；仅 fw.dat→valid + **警告"实测失败 code=13"**；只字库无 fw 无 Res→invalid。测试 3 例。App 侧观测正常(设备回 FSUM_FAIL, 非假成功)。

---

## [OTA·Phase 2d] OTA 测试界面重构：设置首页入口 + 复用连接 + A↔B 循环 + 读版本 + 美化 — ✅ 2026-07-08
按用户反馈重构 DEBUG OTA 测试屏（真机截图验证渲染、无崩溃）。`app:assembleDebug` 绿，APK 已装机。
- **入口移到设置首页**（诊断与维护区，**与「设备维护(DUT)」同级**），DEBUG 门控；从控制台 DangerSection 撤回占位。
- **复用扫描/连接**：设备卡「连接设备/换设备」→ 既有 `ConsoleConnect` 屏（走 `ConnectionRegistry`）；OTA 屏取 registry 首个非参考设备。
- **主动读版本**：独立「设备版本」栏（栏2）的「读取版本」按钮 → 短命 `S7Console.getDeviceInfo().swVer` 即时显示，人工判断升级完成（升级前/后各读一次比对）。
- **设备头卡同步 DUT DeviceHeader 风格**：整卡可点→连接页、名/MAC(mono)、链路 `StatusPill`、`›` 箭头、「点此换设备」——与设备维护一致。
- **A↔B 循环**：`OtaTestViewModel` 双包槽（`pkgA`/`pkgB` + `OtaPkgSlot`）；循环奇数轮包 A、偶数轮包 B（仅当选了有效 B），做 A↔B 交替刷入压测；只选 A 则每轮 A。
- **尾数/链路优化**：链路做成 `StatusPill`（已连接=success/未连接=warning），尾数并入设备名、MAC 小字，撤掉独立 Kv 行。
- **美化**：`Surface`+`RoundedCornerShape(BT.radius)` 卡片、`StatusPill`、`LinearProgressIndicator`、BT 主题 token，对齐 app 设计系统。
- 各轮结果标注用的是包 A/B；遇失败即停。
- **精简（用户反馈）**：`烧录包 B` + `循环次数` 用「A↔B 循环升级」小开关(`advanced`)包裹，关→隐藏(单包 A 单次)、开→展开；删去满屏说明性文字（设备卡/版本/循环副标/禁用原因/顶栏），版本无值显 `—`。start/canStart 尊重 advanced（关时忽略 B、次数=1）。

---

## [OTA·Phase 2c] 首台真机（D8F7）失败根因 → 写流控修复（EC-5 落地） — ✅ 2026-07-08
首次真机 OTA（`apollo4_watch_s7/Docs/log/ota3.log`）在**第 2 文件首个多包切片**即失败——根因定位 + 修复。`app:assembleDebug` 绿，APK 已装机。
- **根因**：`AndroidBleClient.write` 无流控——`writeCharacteristic(NO_RESPONSE)` 发完即返。一切片 17 帧背靠背猛发 → Android GATT 写缓冲溢出、第 2 帧起返 BUSY 被 `safe{}` 静默吞丢 → 设备收不到完整切片 → 15.36s UI 看门狗 abort 断链复位。铁证：fCheck.dat（108B **单帧**）过 → `[1/14]`；fCN26.dat（首切片 3944B **17 帧**）→ `otaTimer 0→15` 零接收 → `DM_CONN_CLOSE_IND`。即设计早标的 **EC-5「切片内逐包背压」真机坑**。
- **修复**：`write` 改**逐帧串行（`Conn.writeLock`）+ 等 `onCharacteristicWrite` 再发下一帧**（No-Response 写 Android 亦回调，作背压节流）；返 BUSY 短退避重试（8 次）；机型不回调 → 300ms 兜底继续（正常回调仅 ms 级，即 BLE 吞吐节拍，不额外拖慢）。`Conn` 加 `writeLock`/`writeAck`，新增 `onCharacteristicWrite` 回调 + `startWrite`。
- **旁证无碍**：STOP 发空 payload（固件忽略，fCheck STOP→`[1/14]` 成功）；fCheck 声明 sliceSize=108（<上限，正常）。
- **✅ 复测全过（ota4.txt，2026-07-08 19:46→19:54，~7min50s / 23.96MB）**：14 文件全传（上次卡死的 fCN26→`[2/14]`，含 ResData 17.9MB）→ `B2A_ResCheck` OK → `OTA_DoRecvAfter`(A→B,Res+字库齐) → `OTA_SetFlag(1)`→`goto SecBL`→`SYS_Reset` **首次真机 OTA 端到端成功**。
- **缺陷范围澄清**：无流控缺陷在**通用写路径**（所有 BLE 发送都走 `write`），但只有 OTA 会触发丢包——控制台/拉日志均"单帧+单飞"（`request` 发一条等一条应答，任意时刻 ≤1 write 在飞），唯 OTA 一切片背靠背连发 ≤17 帧才溢出。修复对所有发送统一生效（单帧命令仅多几 ms 确认）。

---

## [OTA·Phase 2b] DEBUG「OTA 测试」界面：选包→校验→循环刷入 — ✅ 2026-07-08
真机 OTA 测试通道（app 层 + UI）。`shared:jvmTest` + `app:assembleDebug` 全绿，APK 已装机。
- **shared**：`OtaPackageValidator`（纯逻辑，可测）——合法 zip 内容"简单校验"：必推核心 `ResData/ResFat/ResCheck/fw.dat` 存在（缺=硬错）、文件名 ≤12 字符、无 0 字节、缺字库=软警告；`sortByPushOrder` 按 golden 序（字库→fw→Res）。`OtaPackageValidatorTest` 8 例。
- **app**：`OtaZipLoader`（SAF Uri → `ZipInputStream` 解压 → `List<OtaFile>` → 校验 → `OtaPackage`）；`OtaTestViewModel`（选包/循环 N 次遇失败即停/短命 `S7Console` 读版本/registry 回连补登记）；`OtaTestScreen`（目标设备卡+选包+校验展示+循环次数+进度绑 `OtaPhase`/`OtaProgress`+各轮结果+日志，DEBUG-only）。
- **接线**：`Route.OtaTest`；`DeviceConsoleScreen` DangerSection 的 OTA 占位改 `BuildConfig.DEBUG` 门控入口 → `OtaTestScreen`；`AppModule` 注册 `OtaZipLoader`+`OtaTestViewModel`。
- **循环升级测试**：可设次数（默认 1），每轮 下载→自复位→重连→读版本，遇失败即停。目标=registry 首个非参考设备（连 D8F7 即为它）。`AndroidBleClient.connect` 复位后重连已支持（断链 remove+close，再 connect 建新 GATT）。
- 待真机：首台 D8F7 走通；O-1（只推 fw.dat）；续传/错误路径。

---

## [OTA·Phase 2a] 下载后回连 + 读版本显示 编排层 — ✅ 2026-07-08
Phase 2 首块（Mock 可验、无需硬件）。`S7OtaSession.provision` 只到 DoneDownload，设备末 STOP 后自复位、App 观测不到内部生效——本层把"下载后回连 + 读当前版本"闭环。`shared:jvmTest` + `app:assembleDebug` 全绿。
- **不做版本校验**（用户决定）：OTA 包不含版本信息，无法"包版本 vs 设备版本"机器比对 → 改为回连后**读取并显示**当前版本，是否刷成功人工看版本判断。
- **新增 `OtaProvisioner`**：`provisionAndReconnect` = Downloading→WaitingReboot(best-effort 等断链)→Reconnecting(connect+等 CONNECTED,重试)→ReadingVersion(重试)→`Reconnected(currentVersion?)`/`Failed`；下载失败直通。`readVersion` 注入(生产接重连后新 `S7Console.getDeviceInfo().swVer`)；**读不到不算失败**(Reconnected(null)→UI "未知")，仅重连失败 `Failed(ReconnectFailed)`。
- **`OtaResult`**：`DoneDownload`/`Reconnected(currentVersion:String?)`(仅展示非判据)/`Failed`；`OtaFailure` 加 `ReconnectFailed`；新增 `OtaPhase` 枚举。`S7MockWatch.otaRebootAfterComplete` 模拟设备自复位断链。
- **对抗审查修 1 必修（A）**：`readVersionWithRetry` 原对抛异常的 `readVersion` 零防御，而生产读法 `S7Console.getDeviceInfo()` 超时/失败是 *抛* `S7CommandException` 非返 null → 会异常穿透+击穿重试。已修：捕获非取消异常转 null 重试、`CancellationException` 上抛。B（陈旧 CONNECTED 短路，A 修后自愈）留真机期；F 随去掉版本校验自然消解。
- **`S7OtaProvisionerTest` 6 例**：重连读当前版本 Reconnected / 重连失败 / 版本读不到→Reconnected(null) / 版本读抛异常→Reconnected(null)(守 A) / 不复位仍重连读版本(connectCalls==0) / 下载失败透传。
- 下一步：生产 UI 接 `readVersion`(重连后新 `S7Console`) + 展示 `Reconnected.currentVersion`；接采集 `ota_all.zip`（zip 解包 app 层）；attended 工具屏；首台真机端到端。

---

## [OTA·Phase 1.1] 首条真机 golden 日志验证 → 修 BUG-1/BUG-2 分片账 — ✅ 2026-07-08
真机设备侧 OTA 日志（`apollo4_watch_s7/Docs/log/ota.log`，一次完整 14 文件 ota_all 刷写）首次把设计字节假设落到真机报文。验证详情见 [`OTA/implementation-notes.md`](OTA/implementation-notes.md)「Golden 日志验证」。`shared:jvmTest` + `app:assembleDebug` 全绿。
- **坐实**：START 26B 逐字段一致；DATA ack `recvLen`=本切片长(非累计, O-6 解决)；完成路径 DoRecvAfter→SetFlag→Check→SecBL→SYS_Reset 逐步吻合；14 文件全 type=2；MMI 本机即时授权；~40KB/s。
- **BUG-1（真机必挂）**：每帧 param 应 = **MTU−15**（含 3B ATT 写头）而非 MTU−12——真机 `ParamPktLen:232=247−15`、`SliceMaxSize:3944=232×17`。旧 MTU−12 使首帧上链 247B>MTU−3 → Android 写被拒、首切片即挂。修 `S7FileTrans.maxParamPerFrame`+`defaultSliceMaxSize`。
- **BUG-2（防御，后经 ota2.log 翻案）**：`S7OtaSession` REQ 应答改防御式（短应答/12B 不自洽均不 abort、按本地 sliceMax 续）——**次日 `ota2.log`(带 TX)坐实应答实为 12B（`0f 04 0c 00 · 00 01 0e 00 68 0f 00 00 …`），BUG-2 系虚惊**（早前 8B 是内部 SDM 记录非 TX 帧）；防御式改动无害保留。
- **新测 3**：`sliceFragmentation_respectsMtuMinus15_perGoldenLog`（钉 232/3944/17帧/首帧244）、`provision_survivesShortReqReply_usesLocalSliceMax`、`provision_clampsDeviceSliceMax_downToLocalCap`（device>localCap 向下钳制回归）。四视角对抗审查（4 agent）确认可安全落地。
- **权威协议文档成文**：[`OTA/S7采集OTA_指令交互与流程.{md,html}`](OTA/S7采集OTA_指令交互与流程.html)——`ota2.log`(带 TX) + 固件 `b2a_protocol.h` 双坐实，五子命令逐字节(真机 hex + file:line + 解码走查) + 会话时序/累加和/完成流程/错误码，7 图。STOP 16B payload 经固件确认被忽略（`ble2appWrap.c:5400` 只 hexdump），空 STOP 正确。承载=复用控制台 B2A/AMDTP 通道（O-4 解，无需新 GATT 发现）。

---

## [架构] 通用采集框架化分析：8 项决策拍板 + B-lite 实施计划 — ✅ 2026-07-07
架构优化分析会话（10 智能体勘察 7 子系统 + 3 视角盲区批判，采访式 8 问 8 答）：
- **决策 D1–D8 全部拍板**：M9=发版接新协议（DSL 热下发出局）／多 DUT 真需求按设备分文件／设备身份=固件承诺 static+App 锚 MAC／控制面=独立 DeviceOps 语义层／长连接=息屏长采+自动回连／**D6 时间职责分层=包内时间语义(样本时间戳/seq/间隔)归采集协议, App 只打接收时间标签+raw hex 落盘, 不反推/不校准/不排序**／方向终选=B-lite 数据核先行／受试者信息维持明文（M9 前重议）。
- **关键勘察结论**：注册表对 B2A 主线空转（识别/订阅/门控三面硬编码旁路）；真正协议锁=DecodedStream 闭合枚举；startScan(null,LOW_LATENCY) 为息屏零结果+节流最坏组合；droppedPackets 计数点错位（SharedFlow DROP_OLDEST 静默）；会话主键=文件夹名会随事后编辑漂移。
- **实施计划 P0–P5**：P0 传输地基（特征透传/写队列/priority/ScanFilter）→ P1 数据核注册（B2aProfile+流表开放+UI 数据驱动）→ P2 文件格式 v2（sessionUuid/按设备分文件/hexlog 加通道/质量计数）→ P3 长连接保活（席位状态机/回连分段/FGS location/ROM 专项）→ P4 DeviceOps（S7Console 改造+控制台语义化）→ P5 M7 联动。
- 产出：[`设计/架构优化_通用采集框架化.{md,html}`](设计/架构优化_通用采集框架化.html)（4 图：现状旁路/目标架构/席位状态机/会话分段；含 explainer/handoff/quiz + 附录A 外部验证）+ 活文档 [`context/架构优化_实施笔记.md`](context/架构优化_实施笔记.md)。
- **codex 交叉验证**（codex-cli 只读独立读码）：13 条承重事实断言 **11 CONFIRMED / 2 PARTIAL / 0 REFUTED**，无一推翻。修一处事实错（CollectType 6 值/DecodedStream 7 值曾混称"7 值枚举"）；补三实施要点（ScanFilter 可过滤 128 位私有 UUID 须用完整基址、per-device 分文件是六处联动、P0 写队列须接口+Mock+测试同批）。
- 遗留待确认：Nordic 替换时机（本计划推荐续用自写 GATT，与归档评估 D2 口径相左）；S7 功能掩码位含义（找固件）；CDM 保活增强（backlog）。

---

## [OTA·Phase 1] 传输地基：下行多包分片 + FILE_TRANS 编解码 + 独占事务 + Mock — ✅ 2026-07-07
方向 B Phase 1 落码（Mock 全绿；shared 121 单测 + app 单测/assembleDebug 绿）。设计见 [`OTA/S7采集OTA_设计.md`](OTA/S7采集OTA_设计.md)，活文档 [`OTA/implementation-notes.md`](OTA/implementation-notes.md)。
- **新增 shared.s7**：`S7Frame.encodeMultiPacket`（下行多包分片器，首个 App→表 多包场景；不变量=分片→`S7FrameDecoder` 无损重组）、`S7FileTrans`（FILE_TRANS 五子命令编解码 + 9B/12B/OFFSET 解析 + U32 累加和）、`OtaPackage`/`OtaResult`/`OtaFailure` 模型、`S7OtaSession`（独占长事务：REQ→逐文件 START/切片/STOP→末 STOP=DoneDownload；切片 ack 背压+累加和核对+重传）。
- **接口扩展**：`BleClient.negotiatedMtu`（观测，OTA 分片尺寸用）——Mock + 真机 `AndroidBleClient.onMtuChanged` 存值。`S7MockWatch` fileTrans 状态机 + 注入旋钮。
- **三视角对抗审查**（workflow，24 agent）→ **2 真 bug 修 + 3 测试缺口补**：① `sliceMax` 夹本地分帧容量 `(MTU−12)×17`（防低报 MTU 时切片超固件 17 包硬限；**常数后经 Phase 1.1 golden 日志更正为 MTU−15**）；② 切片重传 drain 提到每次发送前（清跨切片陈旧 ack）；补测 EC-7 设备 sliceMax 采纳 / SliceFailed 耗尽 / EC-1 REQ 授权时延分层。OTA 单测 13 例。
- 残余（Phase 2 硬化，O-6）：切片重传"真·迟到 ack 落下一等长等和切片窗口"竞态需真机 ack 语义（recvLen 是否累计）根治；Mock 恒即时回不触发。
- 下一步 Phase 2（真机）：嵌入采集 `ota_all.zip` + attended 工具屏 + 电量门控 + 首台表抓包核对字节序 + O-1 实验（只推 fw.dat？）。

---

## [OTA] S7 采集固件 OTA 设计文档化：真实包坐实 + 盲点/计划落盘 — ✅ 2026-07-07
方向 B（实验室 attended 配置少量表）开工前的分析与文档化。新增活跃工作线 [`OTA/`](OTA/README.md)：[`S7采集OTA_设计.md`](OTA/S7采集OTA_设计.md)+html + [`implementation-notes.md`](OTA/implementation-notes.md)。
- **两轮固件工作流 + 真实 OTA 包检查**坐实：升级包组成（`FileList.dat` 清单、`ota_all` 14 文件 / `ota_part` 4 文件）、推包语义（App 定序、文件名决定落地、REQ 会话级 MMI 异步授权、9B 累加和 App 自核、UI 看门狗 ≈15.36s / 后端 61.44s）、承载=AMDTP GATT（App 只见 B2A Ack、`OTAR_*` 不出 BLE）。
- **推翻两条早期悲观口径**：① BLE OTA **永不碰 SecBL/boot** + `flash_fw.ps1` J-Link 有线**无条件恢复** → 砖化风险"高不可恢复"→"中可救"；② stock→采集 **非 fw.dat 快闪**——Res/字库/fw 全不同，必推采集整包（~19.7–24MB / 15–45min attended）；采集包**已预构建**。
- **首个真机实验 O-1**：能否只推 `fw.dat` 复用旧 Res（30min→2min）。真机可联调，字节序留抓包窗口。
- 下一步 Phase 1：下行多包分片器 + FILE_TRANS 编解码 + BleClient MTU/背压 + OtaTransport 独占事务 + Mock fileTrans + commonTest。

---

## [重构思] architecture 目录消灭：全部入归档 — ✅ 2026-07-07
重新构思期收尾动作，`Docs/architecture/` 目录不复存在：
- `架构评估_20260706.{md,html}`（波次A/B 已收官，P2 残项清单在其正文与 v10 条目）、`02_parser_registry_design.md`（R1–R3 已落码，真相在 `shared.protocol.registry`；R4/R5 要点在 context）→ `归档/`。
- `scenes.json`：**核实发现 Docs 份与 `app/src/main/assets/scenes.json` 已漂移（app 版为准）**——Docs 旧镜像归档，README 冻结句改述"运行真源 = app assets"。
- `architecture/README.md` 随目录消灭（协议路线口径已并入 context 与归档 README）。
- Docs 顶层现仅 6 文件 + 6 目录（context/prototypes/设计/真机证据/UWTP/归档）；全线引用改道，死链复扫活区清零。

---

## [重构思] 协议路线口径修正 + 大归档：M7 = B2A 主体扩展 — ✅ 2026-07-07
**用户口径修正（推翻"二选一"）**：M7 采集协议**不是**"自研 v0.1 vs UWTP 二选一"——正确路线是**在现网 B2A 协议主体上添加/扩展**采集能力；UWTP 仅思考方向（可能借用 ACK 窗口/断点续传/统一响应等部分设计）。**项目进入重新构思期**。
- **自研 v0.1 线整体归档** → [`归档/自研协议线_v0/`](归档/自研协议线_v0/)（bluetrace_v0.proto + 12B 帧规格 md/html + BTCP/1 + btcp1_draft.proto + 示例脚本，附归档 README 说明口径）。
- **s7 协议文档随用户大归档动作入库** → [`归档/s7/`](归档/s7/)（共识稿/分册/下一代稿/CHANGELOG 全套；**仍是 B2A 扩展路线的协议参考真源**，重新构思期作资料库；文档有需要会继续生成）。
- **SPEC §4 加口径注记**（v0.1 方案待重构思，proto 引用改归档路径 ×3）；代码注释 5 处随迁改道（4 处 s7 路径 + FrameHeader）；编译绿。
- CHANGELOG/context/里程碑/两级 README/UWTP 两文档 全线引用改道；UWTP V0.99 html 重生成；死链复扫活区清零。
- **architecture/ 现仅 4 项**：README（导航+新路线图）、架构评估（活）、02 注册式架构（R4/R5 蓝图，协议无关故保留）、scenes.json（机器契约）。
- **下一步 = 重新构思**：以 `S7B2A/S7协议共识规格.md` 为基线设计「B2A 采集扩展」方案（新文档按需生成）。

---

## [文档] 目录整理第七轮：每个 html 配独立 _files 资源夹 — ✅ 2026-07-06
用户口径升级：不允许多个 html 共用资源桶，每个 html 一个专属资源文件夹。`设计/assets/screenshots_v6` 双页共用桶按实际用图拆分（**零复制**——两页用图本就不相交）：
- `设计稿画廊_v6.html` + **`设计稿画廊_v6_files/`**（46 张 design_NN 数字版 + design_index.md 映射）。
- `设计稿与真机对比_v2.html` + **`设计稿与真机对比_v2_files/`**（A 侧 design_中文名 ×6 + B 侧 device_中文名 ×7）。
- `brand/` 提级为 `设计/brand/`（品牌资产不属于单个 html）；`设计/assets/` 目录消失。
- 两页 html 内 JS/文本引用、设计审查报告、CHANGELOG 活引用、README 树全部批改；死链复扫零新增。

---

## [文档] 目录整理第六轮：architecture 已实施方案归档 — ✅ 2026-07-06
`存储与日志设计.md`（v7 已实施，真相在代码）→ `归档/`，5 处活引用改道；architecture/ 现 11 项全部为"活文档或待拍板候选"：评估（P2 在册）、02（R4/R5 蓝图）、自研协议线四件（M7 二选一未拍板，拍板前按纪律保留）、机器契约两件（路径冻结）、s7/（现网协议线）、README + assets（帧规格脚本）。

---

## [文档] 目录整理第五轮：解散 Docs/assets 混桶——资源随使用者打包 — ✅ 2026-07-06
用户口径：html 与其资源文件同文件夹打包，不留归属不明的公共资源桶。
- `assets/screenshots_v6/`（60 张设计稿截图）+ `assets/brand/`（图标/首屏源图）→ **`设计/assets/`**（设计三件套专属资源，三件套引用改同包相对路径）。
- `assets/screenshots_device/` → **`Docs/真机证据/`**（regress_20260706 + waveB_20260706 两个证据集，服务对象是 CHANGELOG/context 记录线而非任何 html）；CHANGELOG/context/里程碑 引用批改。
- `Docs/assets/` 目录消失；顺手清理 Docs 根四个与 s7 正本字节级相同的未跟踪协议文件副本。
- 死链复扫零新增；README 树同步。

---

## [文档] 目录整理第四轮：s7 调试证据归档 + assets 收纳 + 代码注释勘误 — ✅ 2026-07-06
用户点名四目录复查（s7/architecture/assets/prototypes）：
- **s7/**（s7/CHANGELOG 第 23 轮）：60 张联调截图 + 2 devlog → `归档/s7协议工作底稿/assets/`，s7/ 收敛为**纯协议文档目录**（assets 只留 3 个示例帧脚本）；**归属澄清**——protocol-zqdata-uhtp-v1 是 S7 设备线自己的下一代稿（非 UWTP 家族文件），留 s7/ 待改写为采集 Profile。
- **代码注释勘误**：4 个 kt 文件里 `architecture-v2` 旧路径 → 现路径（编译绿）。
- **assets/**：34 张 v6/v7 全屏巡检散图（零活引用）→ `归档/历史截图/device_v7/`；图标/首屏源图收纳 `assets/brand/`；`screenshots_device/` 只剩两个活证据集（regress/waveB）。
- **architecture/ 根**：无进一步动作（上轮已导航+状态行；02/03 编号命名保留——全仓引用密集，改名纯成本）。**prototypes/**：无动作（v4_android.html 真源路径冻结，单文件目录即正确形态）。
- 死链复扫零新增；两级 README 树同步。

---

## [文档] Docs 三轮深度整理：s7 分册五要素 → architecture 导航 → 全库死链清零 — ✅ 2026-07-06
提交 `fd14878` / `955ee7f` / `12f29b7`，同一条整理线的三级递进：
- **s7 子目录**（`fd14878`，s7/CHANGELOG 第 22 轮）：对照协议文档五要素审计——共识稿本达标；补 protocol-zqdata（40B 帧 bit 图 + B2A 封装标尺 + 实例包 decode 脚本实算 + 离线上行状态机）与 protocol-b2a（帧信封 ASCII 标尺 + OTA 状态机图）；plan/review/_raw 十份工作底稿 → `归档/s7协议工作底稿/`。
- **architecture 目录**（`955ee7f`）：新建 `architecture/README.md`(已随目录消灭)（文档角色/状态清单 + **三线协议关系图**：自研 v0.1+BTCP/1 vs UWTP 二选一待固件评审、S7 现网线与 M7 无关 + 变更纪律）；6 份文档状态行对齐现实（02 已落码/存储已实施/自研线补 UWTP 关系）；v0.proto 死引用修复。**不物理合并**：机器契约路径冻结 + 各文档角色不同，缺的是关系说明不是文件合并。
- **Docs 全库**（`12f29b7`）：脚本全库死链扫描——活区 10 处全修（s7 底稿归档的引用断链），归档历史文档内部死链按冻结原则不动，复扫活区清零；md/html 8 对全同步；`assets/{screenshots,device_v5,device_v6,pic}` 92 文件零活引用 → `归档/历史截图/`（compare 考古页目录常量随迁修复）；代码审查报告/设计审查报告补收官状态行；里程碑 3 处过期口径修正（UHTP→UWTP、解码器口径→注册式架构、D2 Nordic 已拍板）。

---

## [v11·波次B] 架构演进：注册式协议架构 R1–R3 + Registry 事件驱动 + iOS 债下沉 — ✅ 2026-07-06
来源：[`归档/架构评估_20260706.md`](归档/架构评估_20260706.md) 波次B（B2/B3/B4）+ [`归档/02_parser_registry_design.md`](归档/02_parser_registry_design.md) 迁移节拍 R1–R3。
- **B4 = 02 设计 R1–R3 落码**：新增 `shared.protocol.registry` 包——`ChannelId`/`ChannelParser`/`ProtocolProfile`/`ProtocolRegistry`/`DeviceParserHost`（R1 骨架）+ `ProtocolEvent` 事件模型（Samples/CommandAck/DeviceEvent/Malformed，R3；Capability/State/AlgoResult/FileChunk 四类 payload 依赖 M7 冻结，R5 再补）+ `MockBleProfile`（R2：MockPacketCodec 装进 Profile 形状）+ `HrsProfile`/`HrsParser`（SIG 0x180D/0x2A37，u8/u16 bpm——R4 心率带真实链路的先行协议，不依赖冻结）+ `RegistrySampleDecoder` 适配器。`SampleDecoder` 增 `onDeviceAttached`/`decodeEvents` 默认方法（旧实现零改动）；会话控制器改消费事件流（Malformed→WARN 诊断，raw HEX 照常落盘）+ start 逐设备 attach；DI 注册表按后端拼装（Mock 后端全员 Mock 线协议 / 真实后端注册 HRS；无 profileId 的自研 DUT 回退 Mock→malformed 告警，等价旧 unparseable 行为）。
- **偏离 02 记录**（务实裁剪）：① `BleNotification` 不加 ChannelId 字段——host 按 characteristicUuid 小写匹配 + 单通道 profile 兜底路由（Mock 通知不带特征 id）；② `CommandEncoder` 与控制面事件消费缓行至 R4/R5（采集面现阶段只吃 Notify）。
- **B2 ConnectionRegistry 事件驱动化 + 下沉**：迁 `shared.ble`，构造注入 `BleClient`+scope；add 时启动 linkState 常驻监听，`DISCONNECTED` 自动清退（被动断连不再依赖调用方手动对齐），`RECONNECTING` 在册（琥珀点语义）；监听复用、CAS 防并发重复启动；调用方主动 remove 与自动清退幂等，四个 VM 调用点零改动。
- **B3 iOS 债下沉**：`CollectDraft` 迁 `shared.domain`（零 Android 依赖）；`DeviceLogStore` 包迁 `data.android`（C4 包名归位）；`Subject.toS7Person()` 域映射下沉 `shared.s7`；控制台全量读编排下沉 `S7Console.readAll()` + `S7Snapshot`（单项失败不阻断、失败项 null→上层保留旧值，语义不变）。**zip 组包不下沉**（有意遗留）：`java.util.zip` 是 JVM 专属 API，commonMain 不可用；iOS 侧接 Apple 压缩 API 时再抽象接口。
- 测试：commonTest 新增注册表线 12 例（HrsParser u8/u16/短包、Registry resolve/byId、RegistrySampleDecoder 路由/回退/Malformed/会话边界）+ ConnectionRegistry 迁移并新增事件清退 3 例（被动断连清退/RECONNECTING 在册/清退后重登记复用监听）；`:shared:jvmTest` + `:app:testDebugUnitTest` + `assembleDebug` 全绿；**CI 首跑绿**（D3 闭环，run 28773182921）。
- 真机冒烟（M2101K9C，Mock 后端走新注册表链路）：连 Polar H10+BT-DUT-0427 → 在线采集 47s → 双设备 HR 出值 + ppg_g/ppg_ir/acc 活跃流 → 结束摘要 5358 行 / HEX OK / 解码 CSV 齐全；证据 [`真机证据/waveB_20260706/`](真机证据/waveB_20260706/)；后端已切回默认真实 GATT。

---

## [文档] UWTP V0.99-r2：完整审查十项修改全部采纳（V1.0 RC） — ✅ 2026-07-06
[`UWTP/UWTP_BLE_Protocol_Design_V0.99.{md,html}`](UWTP/UWTP_BLE_Protocol_Design_V0.99.md) + proto 同步修订。对照完整审查意见 10/10 采纳（修订记录 §23）：**① 时间戳收敛到只属于在线数据**（§5 三层制：CTRL/TIME 校时 / 在线 REPORT·LOG_LIVE 带锚点 / FILE·OTA 不带采样时间；离线时间归文件格式 UOF1，D-2/D-3/D-3a/D-3b）；② FileEntry 删 `mtime_sec`（时间从文件名/文件头取）；③ REPORT record 头 4B→6B（`u24 rel_ms` 取代 ×100ms delta，量程 4.66h）；④ HELLO 增 `profile_id + registry_hash` 同表一致性校验（当前 0x69E89954，不一致仅 CTRL 可用）；⑤ OTA 补 WriteNoRsp 节流（READY +`max_in_flight_chunks`/`inter_chunk_delay_us`，D-16）；⑥ transfer_id 规则（0 无效/断连作废，D-17）；⑦ REPORT 异常解析 7 条（§13.2）；⑧ 校验算法写死（CRC-32/IEEE 全参数 + OTA SHA-256，D-18）；⑨ TUNNEL 明示不保可靠；⑩ SECURITY 量产边界（量产开放 OTA/FILE/DELETE/USER_PROFILE 必须启用鉴权）。另补超时参数（CTRL 3s×2、窗口 ACK 5s，D-15）与 legacy 误判保护（`UWTP_BAD_FRAME` 不回落）。
**坑（已记忆归档，根因后经用户确认）**：**C/D 盘 = 端点透明加密盘、E 盘非加密——从加密盘直接复制文件到非加密盘不解密，`%TSD-Header-###%` 密文原样落地**（python 在加密盘原地能跑 = 透明解密假象；加密异步，新文件短暂明文不可侥幸）。当天四份归档示例脚本全部中招，已用 Write 直写重建并逐一复跑验证（含金帧 CRC 自校验）。规则入全局 skill `win-py-tsd-guard`：永不 C/D→E 复制；落仓 Write 直写 + 验证；C/D 盘写 Python 不加 `.py` 后缀。

---

## [文档] Docs 全目录深度整理：死链清零 + 孤儿截图归档 + 状态行补全 — ✅ 2026-07-06
提交：`12f29b7`。
- **死链扫描（脚本化）**：活区 10 处全修（上轮 s7 底稿归档造成的引用断链 + 归档侧挪动副作用）；归档区历史文档内部死链按"冻结不维护"原则不动。
- **孤儿截图归档**：`assets/{screenshots(v4-v5 轮), device_v5, device_v6, pic}` 全仓零活引用 → [`归档/历史截图/`](归档/)；compare 考古页目录常量随迁修复。
- **状态行补全**：[`代码审查报告_20260706.md`](代码审查报告_20260706.md)（四波收官）、设计审查报告 v6（波次④落地 + ⏳ 活缺口）。
- **[`里程碑与进度.md`](里程碑与进度.md) 3 处过期口径修正**：UHTP→UWTP V0.99、"WireSampleDecoder 替换"→注册式 Profile 接入 + D2 已拍板 Nordic、"解码仍 Mock"→注册式架构已落码。
- [`README.md`](README.md) 目录树：assets 口径更新 + 归档桶补「历史截图/」。

---

## [文档] architecture 目录深度整理：导航 README + 全员状态标注 + 死引用修复 — ✅ 2026-07-06
提交：`955ee7f`。
- **新建 `architecture/README.md`(已随目录消灭)**：文档角色/状态清单 + **三线协议关系图**（自研 v0.1+BTCP/1 与 UWTP V0.99 为 M7 冻结二选一待固件评审；S7 现网线与 M7 无关）+ 变更纪律（状态行强制 / 机器契约路径冻结 / 历史进归档）。
- **全员状态标注对齐现实**：02（R1–R3 已落码 + 两处偏离在册）、03/btcp1、帧规格、`bluetrace_v0.proto`（补 UWTP 二选一关系）、存储与日志设计（已实施 v7）。
- `bluetrace_v0.proto` 修 BlueTrace_Protocol.md 死引用（指向归档路径）；[`README.md`](README.md) 树修正（补评估/architecture README 条目、s7 归档口径、归档桶新增「s7协议工作底稿」）；帧规格 html 重生成。

---

## [文档] s7 协议分册补齐五要素 + 工作底稿归档（第 22 轮） — ✅ 2026-07-06
提交：`fd14878`。对照协议文档标准五要素（Frame 表 / bit-level ASCII 图 / 逐字节 payload / 实例包 decode / 状态机）审计 [`归档/s7/`](归档/s7/)：共识稿达标，两份分册补缺——
- **[`protocol-zqdata.md`](S7B2A/protocol-zqdata.md)**：§3.1 增 40B 帧 bit-level memory map（28B 大端主体 + 12B AGC 尾逐位）；新增 §3.7 B2A 封装上行包字节标尺（小端信封/大端数据分界可视化）、§3.8 **实例包 decode**（HR 起帧 16B 全注解 + 212B 数据包帧0/帧1 逐字段；脚本实算 CRC + 金帧 0x462D 自校验；数值为构造演示值，待采集固件手表实录替换）、§3.9 离线上行发送序列状态机（门槛/流控/截尾/OTA 抢占全 file:line 实证）。
- **[`protocol-b2a.md`](S7B2A/protocol-b2a.md)**：§2 增帧信封 bit-level 标尺图；§9.1 OTA 状态机扩为 ASCII 图（REQ→READY→START→TRANS→END + 60s 超时/STOP/ERROR 回边 + OFFSET 断点续传）。
- **目录归档**：`plan.md` / `review-report.md` / `_raw/`（10 份工作底稿）→ `归档/s7协议工作底稿/`；保留活文档 command-status（实现追踪）与 completeness-audit（缺口清单）。两份 html 重生成。明细见 [`归档/s7/CHANGELOG.md`](归档/s7/CHANGELOG.md) 第 22 轮。

---

## [文档] UWTP 统一可穿戴传输协议 设计 V0.99（冻结候选） — ✅ 2026-07-06
[`UWTP/UWTP_BLE_Protocol_Design_V0.99.{md,html}`](UWTP/UWTP_BLE_Protocol_Design_V0.99.md) + 契约草案 [`UWTP/uwtp_v0.99_draft.proto`](UWTP/uwtp_v0.99_draft.proto)：UHTP V4 的补全审议定稿版（改名 UWTP，D-1~D-14 决策表全记录；**协议家族已归拢至 [`Docs/UWTP/`](UWTP/README.md) 独立目录**，含前身 UHTP V4）。**Core 补齐 V4 六大空白**：GATT 绑定（1 Service + RX WriteNoRsp + TX Notify；S7 复用 ZQDATA 特征、其他项目占位）、时间模型（UTC 系统钟 + u32 秒/u16 ms/s16 时区仅 TIME 域一次 + 数据面只带 ms 偏移的两级时间制）、分域断连语义（OTA 重协商续断点 / FILE 从头重传 / 在线丢了就丢）、并发矩阵（OTA 独占最高优先）、每域丢包容忍度与完整性分层表、安全姿态（Just Works + 预留一次性鉴权）；**统一响应模型**（NEED_RSP 回显 seq + 响应 Protobuf 首字段恒 status）+ 固定字段全小端铁律 + 静态注册表制（不做动态能力发现，文档即共识）。**Domains**：心跳砍掉（BLE 链路监督兜底）；LOG 导出并入 FILE 域（多文件名 + LIST + DELETE，路径隐含约定）；OTA 语义对齐 Zephyr MCUmgr/SMP img_mgmt（槽位/状态位/test-confirm-rollback 映射表）；新增 TUNNEL 透传域。6 组示例帧脚本实算（[`UWTP/assets/gen_uwtp_examples.py`](UWTP/assets/gen_uwtp_examples.py)）。**下一步**：S7 采集 Profile 改写（protocol-zqdata-uhtp-v1 按 D-6/D-8/D-12 删改）→ 固件评审 → 双端金帧联调 → 冻结 V1.0。

---

## [v11·波次A] 架构演进：P0 线程修正 + 依赖环消除 + CI — ✅ 2026-07-06
提交：`3a353ad`（19 文件）。来源：[`归档/架构评估_20260706.md`](归档/架构评估_20260706.md)（D1/D2/D3 已拍板：R1–R3 落码 / 传输选 Nordic / 上 CI）。
- **A1 采集落盘挪出 CPU 池**：会话事件循环注入 `Dispatchers.IO.limitedParallelism(1)`（进 IO 弹性池且保持单线程串行语义）；建目录/首写 manifest 一并挪进会话 IO 协程（消架构#4 主线程 IO），init 失败走 ERROR 收尾。
- **A2 全局异常兜底**：应用级 scope 挂 `CoroutineExceptionHandler` → DiagnosticsLog ERROR；logWriter scope 兜底走 logcat（防自写递归）。
- **A3 仓库层自守线程**：`SessionStore`/`DeviceLogStore` 公开方法改 suspend + 内部 `withContext(注入 io)`；删光 7 处调用点散落切换（含 3 处 Composable 内）。
- **B1 依赖环消除**：`MockBleClient`(+Test) 迁 `ble.mock` 子包——`ble↔protocol`、`ble↔s7` 两个包级双向环消失，为 02 设计 R2 铺路。
- **CI（D3）**：`.github/workflows/ci.yml`——push/PR 跑 jvmTest + app 单测 + assembleDebug，失败上传测试报告。
- **文档详版**：架构评估扩写——新增 §0「机制速览」（协程派发器/异常传播/进程生命周期/Compose 状态/ViewModel/DI/KMP 源集/GATT 单飞，8 个机制各配嵌入式类比，面向非 Android 背景读者）+ P0/P1 逐条背景展开 + 拍板记录。
- 验证：构建 + 全部单测绿。**下一波（波次B）**：B2 Registry 事件驱动化+下沉、B3 iOS 债下沉、B4 = 02 设计 R1–R3。

---

## [文档] 架构评估：现状、问题与演进 — ✅ 2026-07-06
[`归档/架构评估_20260706.{md,html}`](归档/架构评估_20260706.md)（2 张 SVG：现状分层问题标注图 + M7 目标数据流图）。输入 = 四路代码审查 + 本轮**结构层审计**（模块/包依赖矩阵、KMP 边界、DI、协程纪律逐项核对）。
- **保持项**：commonMain 零泄漏/零 expect-actual、单消费者串行化、hexlog source-of-truth、版本目录、s7 逻辑下沉（1094 行+4 测试类）、实现引用收敛 DI 组装点。
- **新发现 P0**：采集落盘跑在 `Dispatchers.Default` CPU 池（AppModule:53 + 会话 runScope 继承）；全仓零 `CoroutineExceptionHandler`；IO 切换散落 7 调用点（3 处在 Composable）。
- **P1 结构**：包级双向环 ×2（ble↔protocol、ble↔s7，Mock 混放接口包所致）；ConnectionRegistry vs linkState 状态双真相；iOS 债（ConnectionRegistry/CollectDraft/S7Person 映射等纯 Kotlin 写在 app）；协议接入仍全局单解码器 → **落地 02 注册式设计 R1–R3（不等冻结）**。
- **P2 卫生**：DI 单模块+裸泛型、UI 19 处 koinInject 绕 VM、DiagnosticsLog 下转型、DeviceLogStore 包名、会话索引、CI 缺失。
- **三个待拍板**：D1 R1–R3 现在落码（建议是）；D2 采集档传输 Nordic vs 自研补五件事（倾向 Nordic）；D3 上 CI（建议是）。

---

## [文档] 里程碑与进度 全面刷新 — ✅ 2026-07-06
[`里程碑与进度.md`](里程碑与进度.md) 从 2026-06-24 口径刷新到当前：新增 **M6.1 权限/环境态加固**、**M6.2 真实 BLE + S7 控制台**（M7 传输半边提前落地 + Mock/真实可切换）、**M6.3 审查修复线 v8–v10**（四波收官 + 真机回归）、**M6.4 文档与协议规格线**（Docs 中文化、帧规格、S7 共识稿、UHTP V1 设计稿）四个已完成里程碑；**M7 范围收窄为"采集协议解码"**（冻结路径：UHTP V1 评审 / 自研 .proto 二选一）；修正两处过期口径（"本地未推送"、"BLE/DUT 全程 Mock"）；⏸ 清单同步（DUT 维护已落地、离线采集实壳改挂 UHTP 冻结）；质量/验证状态与下一步优先级重写。

---

## [v10·波次④] 审查修复：UI 对齐 + 可达性（审查修复线收官） — ✅ 2026-07-06
提交：`210da19`（11 代码文件）。来源：[`代码审查报告_20260706.md`](代码审查报告_20260706.md) 波次④（界面 2/3/4/5/6/7/9/10 + 8 部分）+ 真机回归新发现。
- **原型裁决跟进**：运行屏 ♥ 心率 `error` 红 → `primaryDeep`；长按「结束」红底 → **主蓝胶囊**（红独占错误裁决）；用户选择/编辑面板/GNSS 勾选的整套紫选中态 → **主蓝**（紫只留实体图标）。
- **形状统一**：EntryTile/ListTileRow 图标盒 圆形 → **圆角方 r10**、PrimaryButton/OutlineBtn 14dp → **胶囊 999**（对齐原型 `.ico`/`.btn`，真机抽查 `19_wave4_home` 生效）。
- **可达性**：长按结束补 `Role.Button` + 无障碍长按动作（返回硬锁定下 TalkBack 用户的唯一出路，界面#4）；5 处顶栏文字动作补 `minimumInteractiveComponentSize` ≥48dp（数据全选/选择、详情全选、用户保存、场景完成、权限授权 pill）。
- **其他**：长按遮罩硬编码亮色 → `BT.bg` 随亮暗（暗色不再发白）；实时流 LazyColumn 换稳定 id key（`NumberedLine`，高频包不再全表重组）；DEBUG 演示按钮下移 120dp 不再遮 pill；删 `MonoSmall`/`MonoBody` 死 token（双真源隐患）。
- 验证：构建 + 单测全绿 + 真机抽查。
- **审查修复线收官**：四波全部完成，52 条中高危/中危主体已清。剩余为低优先与待条件项——架构 4（start 主线程 IO）/11（会话索引入库）/12/14、界面 8（typography 全线接线）/12（场景 en 词表）/13/15 部分、交互 13（按 s7 新连接页复评）/15（多窗口，待真机分屏）、s7 增量清单中低项（写确认、重组上限、pullLog 完整性、对时时区等）——按需另起小轮。

---

## [文档] ZQDATA·UHTP V1 协议重设计（离线优先，设计稿） — ✅ 2026-07-06
[`归档/s7/protocol-zqdata-uhtp-v1.{md,html}`](归档/s7/protocol-zqdata-uhtp-v1.md) + 契约草案 [`zqdata_uhtp_v1_draft.proto`](归档/s7/zqdata_uhtp_v1_draft.proto)：以 UHTP V4（[`UWTP/UHTP_BLE_Protocol_Design_V4.md`](UWTP/UHTP_BLE_Protocol_Design_V4.md)，5B 头/事务域状态机/Protobuf 协商/Report TLV/offset 传输；已归拢至 `Docs/UWTP/`）为基线的 ZQDATA 重设计。**范围**：离线数据回传（主体，FILE 域深化：目录分页 + 窗口 ACK 授信 + 断点续传 + 整档 CRC32 + 显式删除）、在线数据控制透传（新增 TUNNEL 域，汇顶字节原样进出）、算法结果上传开关（ALGO_CTRL + REPORT_TLV）、个人信息写读（USER_PROFILE）；HELLO 能力协商 + NTP 式对时 + content_format 注册表（现网格式原样回传，推荐迁移 UOF1 统一离线格式）。legacy 共存：0xBB/0x1? 首字节分流 + HELLO 探测回落。示例包 protobuf wire+CRC32 实算（[`assets/gen_zqdata_uhtp_examples.py`](S7B2A/assets/gen_zqdata_uhtp_examples.py)）。状态：设计稿待固件评审冻结（开放问题 §13）。s7 线明细见 [`归档/s7/CHANGELOG.md`](归档/s7/CHANGELOG.md) 第 21 轮。

---

## [真机回归] 波次①②③ + s7 合并 + Mock 切换 全链路 — ✅ 2026-07-06
设备：Xiaomi M2101K9C / Android 13，adb 驱动全程截图取证（[`真机证据/regress_20260706/`](真机证据/regress_20260706/)）。

| 回归项 | 结果 |
|---|---|
| Mock/真实可切换绑定 | ✅ 默认真实 GATT；设置 DEBUG 行开启→重启→扫描出全部 Mock 设备（含 S7-FCC4 带 B2A 标识）；回归后已关回默认 |
| 幽灵运行页（交互1·高危） | ✅ `am crash` 后从任务恢复 → 自动弹回采集主界面 +「上次采集异常中断，已保存」toast，不再困死/崩溃 |
| 僵尸前台通知（架构3） | ✅ 崩溃后 sticky 窗口内外均无残留「正在采集」通知（dumpsys=0 条） |
| 暂停语义（交互10） | ✅ 按钮「暂停滚动/继续滚动」；pill 转橙「显示已暂停」；数据框冻结在 01:30 而计时走到 01:32（落盘未停实证） |
| 采集类型空选防呆（交互14） | ✅ 全取消 →「至少保留一路采集类型」警示 + 确定灰禁 |
| 「选择」不预选（交互2） | ✅ 进多选 = 已选 0 |
| 批量删除确认（交互2·高危） | ✅ 「将永久删除 1 个会话（共 0.3 MB），不可恢复。」 |
| 勾选导出做真（v9 裁决项） | ✅ 取消 raw 后按钮变「导出所选（5 项）」；产物 `*_partial.zip` 55KB（272KB 的 raw 被排除）+ 完成 Toast + Download 落盘实证 |
| 应用日志空态/禁用（交互12） | ✅ 「暂无日志」占位 + 导出按钮灰禁（崩溃后尾窗为空，恰好验证空态） |
| 崩溃会话归档（波次①链路） | ✅ 数据页出现 5 段名会话、结束原因 interrupted、质量丢包 0 |

**设计对比**（采集主页 vs design_01）：信息架构/布局/留白一致；差异集中在已登记的波次④形状层（图标容器真机圆形 vs 设计圆角方、CTA 14dp vs 胶囊），无新增回归。
**新发现（低 → 归波次④）**：DEBUG 演示按钮（注入断联/模拟存储满）与顶栏状态 pill 重叠遮挡（仅 DEBUG 构建可见）。
**确认既有遗留**：autoFinalize 时长虚高（显示 02:44 vs 实采 ~1:40）与「0 行」计数 = 架构#14（低）。
**未覆盖**：真实 S7 手表链路（现场无手表）、多窗口蓝牙死角（交互15，需手工分屏操作）。

---

## [v9·波次②] 审查修复：反馈契约——勾选导出做真 / 导出可取消 / 权限哑弹闭环 — ✅ 2026-07-06
提交：`8480af1`（14 文件）。来源：[`代码审查报告_20260706.md`](代码审查报告_20260706.md) 波次②（交互 6/7/9/10/11/12/14 + 界面 1/11 + 架构 13）。
- **勾选导出做真**（用户裁决）：`exportSession` 支持相对路径过滤，部分勾选打包 `*_partial.zip`（token 恒英文）；详情页按钮动态：部分选 →「导出所选（N 项）」、全选 →「导出整夹」、零选 → 禁用。
- **导出可取消 + 模态**：导出任务移**应用级 scope**（页面销毁不再把 zip 写一半就取消）；进行中遮罩 scrim + 拦截穿透点击 + 取消按钮；exporter 取消路径删除 IS_PENDING 记录（Download 不再留幽灵文件）、`CancellationException` 不再被吞、`exportLog` 输出流为 null 不再误报成功。
- **应用日志页**：toast 流接上（导出/清空有结果反馈）、空态占位、行时间戳 epochMs → HH:mm:ss.SSS、空日志禁用按钮。
- **权限哑弹闭环**：缺权限弹层「去授权」遇永久拒绝 → 标记 BLOCKED + toast + 直接带去应用设置页；弹层标记 rememberSaveable（转屏不复活）。
- **蓝牙已关闭页**订阅环境态：开蓝牙回来自动返回（原为静态死页）。
- **暂停语义**：按钮改「暂停滚动/继续滚动」（澄清落盘不停）；运行 pill 随状态切换（显示已暂停 / 等待重连）。
- **采集类型空选防呆**：空选禁用确认 + 警示。
- 验证：构建+单测全绿。**遗留**：交互#13（扫描列表 RSSI 抖动重排）对象已被 s7 连接页取代、按新页复评；交互#15（多窗口蓝牙死角）待真机轮验证后再做。

---

## [合并] feat/s7-device-console → main + Mock/真实可切换绑定 — ✅ 2026-07-06
提交：`eb47c00`（本地合并）+ `ce6a37a`（与远端 PR #2/#3 历史汇合，零内容差异）。18 提交入主线：**真实 BLE**（AndroidBleClient v1）、**S7 手表控制台**（对时/设备信息/写用户/固件日志拉取 → Download/BlueTrace/logs + 查看页）、连接页重做（共用过滤条/RSSI 滑块/支持置顶/扫描权限门）、B2A+zqdata 协议规格随分支带入 `归档/s7/`（protocol-b2a、protocol-zqdata、子 CHANGELOG）。
- **可切换绑定（审查 s7#1 落地）**：`BleBackendSwitch`（SharedPreferences 同步读，DI 启动时决定）默认**真实 GATT**；设置页新增 DEBUG 行「使用 Mock BLE」（重启生效）——Mock 演示/UI 回归链路不再因合并失效。
- 冲突解决：AppModule 用可切换绑定取代两侧硬绑定；MockBleClient 保留双方（接口别名 + S7 模拟段）。
- 口径：`SampleDecoder` 仍 Mock——真实模式下采集只落 raw HEX（source of truth）+ unparseable 告警，解码待 M7 协议冻结。
- 验证：`:shared:jvmTest`（含分支 S7 帧层测试）+ `:app:testDebugUnitTest` + `:app:assembleDebug` 全绿；真机回归（波次① 项 + 合并后链路）待统一跑。

---

## [v8·波次③] 审查修复：M7 前置接口债（真实 BLE 适配层） — ✅ 2026-07-06
提交：`95b409b`（9 文件）。来源：[`代码审查报告_20260706.md`](代码审查报告_20260706.md) 波次③（架构 8/9/10 + s7 合并前置）。**接口形状刻意对齐 `feat/s7-device-console`**：`write()` 留给该分支带入、新增成员插位避开其 diff 锚点，把未来合并冲突面压到最小。
- **BleClient**：+`onAdapterStateChanged`（"蓝牙关→已连设备转重连中"从 Mock 演示钩子升级为**接口产品行为**，真实实现默认 no-op——适配器关闭时 GATT 自然收到断连）；+`debugInjectDisconnect`（演示钩子进接口，默认 no-op）；`connect()` 失败契约文档化（不抛业务异常、以 linkState 收敛、取消须释放资源）；`BleNotification` +`characteristicId`（多特征设备分流解码，默认 null，s7 构造点零改动）。
- **SampleDecoder**：+`onSessionStart`/`onDeviceReset` 生命周期钩子（decoder 是跨会话全局单例，Wire 解码器的分片重组/pktSeq 状态必须在会话边界与断连时清空）；编排层已接调用点（start / 断连转重连）。
- **背压契约（架构#9）**：Notif 数据路改 `trySend` 满即丢 + 计数 → `quality.droppedPackets` 不再恒 0（真实 GATT 回调线程不可挂起，挂起式 send 在真机不可行）；链路事件仍可靠投递；raw/CSV 落盘改 **tick(200ms) 批量 flush**（崩溃损失窗口 = tick 间隔），去掉逐行 flush 的 syscall 瓶颈。
- **解耦（架构#10 + s7#1 前置）**：`CollectionService` 与 `DefaultSessionController` 只依赖 `BleClient` 接口（删 MockBleClient 注入与 `as?` 强转）；AppModule 标注 Mock/真实**唯一切换点**（可切换绑定随 s7 合并时落地，AndroidBleClient 在该分支上）。
- **验证**：`:shared:jvmTest` 全绿（controller 10 例，+2 新用例：decoder 生命周期钩子、tick 周期刷盘）+ `:app:testDebugUnitTest` + `:app:assembleDebug`。
- **遗留**：架构#11（会话列表 SQLite 索引）属性能项、非合并阻塞，延后单独做。

---

## [v8·波次①] 审查修复：数据安全与崩溃加固 — ✅ 2026-07-06
提交：`0c53b25`（18 文件）。来源：[`代码审查报告_20260706.md`](代码审查报告_20260706.md) 波次①（架构 1/2/3/5/6/7 + 交互 1/2/3/4/5/8）。

**shared（故障路径设防）**
- `DefaultSessionController`：消费循环 try/catch 兜底——落盘 IO 异常（磁盘满最典型）不再崩进程，自动以新增的 `StopReason.ERROR` 收尾；收尾路径 finalize/manifest 写失败不抛（会话留"开口"，下次启动 autoFinalize 兜底修复）；循环退出前 close+drain 渠道、给排队中的 Stop 补 ack（存储满自动结束 × 长按结束竞态时 `stop()` 不再永久挂起）；`stop()` 改幂等可空（无活动会话返回 null，不再 `error()` 崩 UI 协程）；生产者 `sendOrDrop` 容忍渠道已关。
- `SessionStore.writeManifest`：临时文件 + `atomicMove` 原子替换——结束时刻被杀不再留截断 JSON（此前会话会从列表静默消失）；`editSession` 半途失败的回滚因此完备。
- `SqlDelightSubjectRepository`：`upsert/delete/setCurrent` 补 `withContext(io)`（此前在主线程写 SQLite）。

**app（进程死亡纵轴 + 破坏性操作契约）**
- `CollectionService`：sticky 重启且非 COLLECTING → 立即 `stopSelf()` + `START_NOT_STICKY`（消灭冻结在 00:00:00 的僵尸"正在采集"通知）；状态收集协程只起一次。
- 幽灵运行页防护：运行页检测 READY+无摘要 → 自动弹回采集主界面（此前用户被硬锁返回困死、长按结束必崩）；进运行页导航一律 `launchSingleTop`（双击/转屏不再重复压栈）；`showTypeSheet`/`labelText` 改 `rememberSaveable`（转屏不再重弹抽屉/丢标签草稿）。
- `startSession()` 采集中重入直接返回；删除当前用户后采集人显示"未选择"并拦截开始（不再静默回退列表第一人，防 manifest 写错采集人）。
- 删除确认：批量删会话加 AlertDialog（列条数+总 MB）+ 删后 toast，顶栏「选择」进多选不再暗中预选第一条；删用户加确认框。摘要屏新增 ERROR 停止原因文案（zh/en）。

**验证**：`:shared:jvmTest` 全绿（controller 8 例 + store 7 例，含 4 个新用例：IO 异常自动收尾且 stop 不挂、竞态 stop 仍返回、无会话 stop 返回 null、manifest 原子性）+ `:app:testDebugUnitTest` + `:app:assembleDebug`。**真机门未跑**（幽灵运行页/僵尸服务需 adb 杀进程验证，下轮统一回归）。
**遗留**：损坏 manifest 的"占位可见"UI 未做（原子写已消根因）；交互 #9/#10/#15（权限哑弹、暂停语义、多窗口蓝牙死角）归波次②/④。

---

## [文档] S7 手表协议共识规格（B2A + 采集固件专用协议） — ✅ 2026-07-06
[`归档/s7/S7协议共识规格.{md,html}`](S7B2A/S7协议共识规格.md)：跨项目共识稿（固件双仓 ↔ BlueTrace），**全部字段出自固件代码**并标注 file:line——`E:\1\apollo4_watch_s7`（开发仓，B2A 基线）+ `E:\1\apollo4_watch_s7_collect`（采集仓）。覆盖：GATT 服务地图（AMDTP FFE0 / GH3X2X 190E ↔ ZQDATA 45121540 互斥切换 `svcSwFlag[0]`、开发仓新 zqdata 模块 0x0A10 段）；B2A 信封逐字节/逐位（0xBB 头 + 命令头 + CRC16-CCITT-FALSE + 多包）；**采集仓新增协议**：DC 采集会话（TEST 0x10/0x11/0x12，IDLE/COLL/READY/TRANS 状态机 + EOF 次序保证）、`ecg_raw.dat` v2 文件格式（32B 头 + 8B 帧头流 + ACC 段回填）、ZQDATA 上行（40B→28B 重打包、HR/SpO2 偏移差异、18B gsensor、212/240B 包核算、228B 上限、svcSwFlag[6] 互斥）；7 组示例包 decode（含真机产测金帧 0x462D + 实算帧，脚本 [`S7B2A/assets/gen_s7_protocol_examples.py`](S7B2A/assets/gen_s7_protocol_examples.py) 带金帧自校验）；错误处理汇总 + App 侧实现共识 + 双固件差异表（依据两仓 merge-base `c6f87d36` 的 git diff 实证）。4 张 SVG + ASCII 位图。深度参考指向 s7 分支 protocol-b2a/zqdata（未合 main）。

---

## [文档] BLE 协议规格开发者版（帧布局/位图/实例包） — ✅ 2026-07-06
[`归档/自研协议线_v0/BLE协议帧规格_开发者版.{md,html}`](归档/自研协议线_v0/BLE协议帧规格_开发者版.md)：把 SPEC §4 + `bluetrace_v0.proto` 重排成实现视角——12B 帧头逐字节/逐位布局（表 + ASCII + 5 张 SVG）、TLV（T=msgType/L=payloadLen/V=protobuf）注册表、4 个**实算并自回验**的示例包 decode（HighFreqBatch 逐字节、Command→一包双帧 Ack+DeviceState、DeviceCapability 分片×3 重组、Mock 格式）、解帧/重组/控制闭环/pktSeq resync 状态机、错误处理汇总。生成脚本存档 [`归档/自研协议线_v0/gen_frame_examples.py`](归档/自研协议线_v0/gen_frame_examples.py)（protobuf wire 编码 + CRC-8 poly 0x07/init 0x00 MSB-first 实算，反射约定标注为待冻结项）。**内容零新增规格**，冲突时以 SPEC §4 / `.proto` 为准。

---

## [v7] 存储 / 日志重构 — ✅ 2026-06-24
提交：`f38ce01`（日志 → 滚动 .log）、`2c248a3`（用户 → SQLDelight 用户表）。**本地 main 未推送**。
设计文档：[`归档/存储与日志设计.md`](归档/存储与日志设计.md)（v8 审查口径 #1–#13；已实施，2026-07-06 归档）。demo 阶段**直接替换、不做迁移**。Workflow 编排 + 真机 `adb pull` 取证。

**应用日志 → 滚动 `.log` 文件（`f38ce01`）**
- `FileDiagnosticsLog`（shared/commonMain）：单个长生命周期 `appendingSink` + 单线程 writerScope（`Dispatchers.IO.limitedParallelism(1)`）串行写、每行 flush、跨午夜按 `app-YYYY-MM-DD.log` 切文件、保留最新 7 天。
- 内存尾窗 `entries`(StateFlow) 供「应用日志」屏；`DiagnosticsLog` 接口 add/clear/export 不变（消费方零改动）。
- 崩溃 handler：未捕获异常先 `cancelWriter()` 再 `appendBlocking()` 同步落盘（异步 add 在进程被杀前刷不到盘）。
- `logsDir = getExternalFilesDir(null)/logs`（与 sessionsRoot 同根、adb pull 可取）；导出复用 `MediaStoreExporter` → `Download/BlueTrace/logs/`；行格式带毫秒（`epochMs%1000`）。
- 单测 `FileDiagnosticsLogTest`：格式/毫秒、appendBlocking 同步、跨午夜切文件、clear 删、retain 裁剪。

**用户 Subject → SQLite（SQLDelight 2.3.2，KMP commonMain）（`2c248a3`）**
- 表 `Subject.sq`：`subject`(id/alias/sex/birth/heightCm:INTEGER/weightKg:REAL/note) + `app_meta`(key/value) 存 currentId；`__default__` 伪用户**永不入** subject 表（currentId 以字符串存 app_meta）。
- `SqlDelightSubjectRepository`（commonMain，iOS 可复用）：`io: CoroutineContext` 注入（commonMain 无 `Dispatchers.IO`）；`upsert` 先 `countById` 仅「新建」才设当前、编辑老用户不动 current；`heightCm` Int?↔INTEGER Long? 双向转换、`weightKg` Double?↔REAL 直配；`delete` 仅当删的是当前才清 current。
- DI（AppModule）：`single<SqlDriver>{AndroidSqliteDriver(BlueTraceDb.Schema, ctx, "bluetrace.db")}` + `single{BlueTraceDb}` + `SqlDelightSubjectRepository(get(), Dispatchers.IO)`；**删 `DataStoreSubjectRepository` + `subjects_json`/`current_subject_id` 键**；`SubjectRepository` 接口零改动（消费方无感）。
- **SQLDelight 2.1.0 → 2.3.2**：2.1.0 的 Gradle 插件访问 AGP 9 已移除的 `BaseExtension`（issue #5940）→ KMP+AGP9 下 codegen 失败；升 2.3.2（官方兼容 AGP 9 新 DSL / Built-in Kotlin，**非降级项目**）即解。驱动分源集：commonMain=`runtime`+`coroutines-extensions`、app=`android-driver`、jvmTest=`sqlite-driver`(JDBC)。
- 单测 `SqlDelightSubjectRepositoryTest`（落 **jvmTest**——`JdbcSqliteDriver` 是 JVM-only 工件、commonTest 元数据编译不可见，内存库）：新建即当前 / 编辑不动 current / 删当前清当前 / 删非当前留当前 / heightCm·weightKg 往返含 null / setCurrent 设清 / `__default__` 可存为当前。

**编排 / 验证**
- 落地顺序：Phase A 日志（独立低风险，先做）→ Phase B SQLDelight；Phase C **4 路对抗式 review**（口径 #8–#13，**4/4 PASS、0 blocking**）→ Phase D 真机门。
- **构建硬验收**：codegen（`:shared:generateCommonMainBlueTraceDbInterface`）+ `:shared:jvmTest`（12 例）+ `:app:assembleDebug` 全绿。
- **真机硬门**（M2101K9C / Android 13）`adb pull databases/bluetrace.db` 取证：建 TEST/TESTTWO → `app_meta.current_subject_id`=TESTTWO（新建即当前）；编辑 TEST→TESTEDIT 后 current 仍 TESTTWO（编辑不动当前）；删当前 TESTTWO → current 移除、仅余 TESTEDIT；`heightCm=170(int)`/`weightKg=70.0(real)` 类型正确、`subject` 表 `__default__` 计数=0。
- **不动**：偏好（主题/语言/场景/首启）留 DataStore；会话原始数据留文件 + manifest JSON；scenes.json 留 assets。选型理由：shared 是 KMP（未来 iOS），SQLDelight 跨端复用同一数据层。

---

## [v6·修订] 对比页设计稿重截（定尺手机框）— ✅ 2026-06-24
[`设计稿与真机对比_v2.html`](设计/设计稿与真机对比_v2.html) 左列 6 张设计稿重截。**本地 main 未推送**。
- **问题**：原 `design_*.png` 按「整页展开到全内容」渲染，宽高比 0.38–0.72 乱跳；短屏（用户选择 0.72）被压成宽扁块，与右侧真机图（0.45）并排一高一矮，显得滑稽。
- **定位**：原型 `.phone` 本就是固定 **330×716（ar 0.46，与真机同比例）**；逐屏实测 6 屏内容均满一屏不溢出（`scrollHeight==clientHeight`），「展开到全内容」多余且有害。
- **修复**：从 `prototypes/v4_android.html` 逐屏按固定手机框（含外框/刘海、白底 #f7f9fc）重截，DPR3 → **990×2148（ar 0.461）**，对齐真机 0.45，内容完整不截断。
- **验证**：对比页 6 对全部**等高 560×560**（设计 259w / 真机 253w 几近同宽）；1440 / 1280 / 680 三档视口均无变形、无横向溢出。
- **涉及**：6 张 `设计/设计稿画廊_v6_files/design_*.png` + 对比页副标/脚注 2 行文案改「定尺渲染」；页面 CSS 原样（中途误改已撤回）。

---

## [v6] 场景模型 + 命名落地 + 用户重设计 + 摘要/详情编辑 — ✅ 2026-06-24
提交：`7df7304`（实现，**本地 main 未推送**）、`b6a97f4`（构建 prompt）。把"采集场景"从 v5 占位变 **JSON 驱动真模型**。

**地基（shared，可单测）**
- 新增 `Scene/SubScene/SceneSelection/SceneCatalog`（`assets/scenes.json` 驱动，与 `architecture/scenes.json` 同源）。
- **采集场景默认 = scenes.json 第一个主场景的第一个子场景**（`SceneCatalog.firstSelection`，去掉 `default` 字段）；本次选择**持久化**到 `AppPreferences`（`scene_main/sub`），启动保留上次。
- **5 段文件命名**：`sessionFolderName` → `主场景_子场景_用户_日期_时间_MAC后四位`（token 恒英文，如 `Wear_Wearing_TEST_20260624_140039_0427`）；manifest 记 `mainScene/subScene`。
- `SessionConfig/Summary/Manifest` 用 `SceneSelection` 取代 `CollectMode`（**删除 CollectMode**）。
- **Default 伪用户**（`DEFAULT_SUBJECT`）；子场景 ∈ `autoDefaultUserSubs`（仅 `Unwear`）→ 采集主界面自动切 Default 用户。
- `SessionStore.editSession`：事后改采集人/场景 = 重写 manifest + 按新 5 段名重命名文件夹（冲突回滚）。
- 「佩戴」子场景按裁决**精简为 佩戴中 / 未佩戴**（scenes.json 两份 + 原型同步）。
- 单测：`NamingAndFormat`/`SessionStore` 更新 + 新增 `SceneCatalog`(firstSelection)/`editSession` 用例。

**页面（app）**
- 场景选择页（新 `Route.SceneSelect`）：左主场景 / 右子场景两级单选，写持久化 `CollectDraft` 回采集 tile。
- 用户选择重设计（用户A）：Default 行 + 单一「用户」单选 + [+新建][确认]；返回=确认；再点取消选择；**每行 ✎ 进编辑、编辑页加「删除用户」**（表单 `verticalScroll`，小屏可达底部）。
- 用户编辑重设计（用户C）：别名仅英数实时过滤 + 出生 **M3 DatePicker** + 身高/体重 **M3 Slider 刻度尺**。
- 结束摘要 / 会话详情：加「✎ 修改采集人/场景」→ 共用 `ModalBottomSheet` → `editSession` 落地（摘要留屏用新名导出 / 详情回列表）；详情摘要行改「采集人·场景」。
- 运行/摘要/详情副标 `mode.label` → 场景中文（`sceneLabelZh`）。
- 示例用户由 **shb 换为 TEST**（原型 + 设备数据）。

**编排 / 验证**
- Workflow 编排：Phase0 地基（主循环）→ Phase1 Plan（3 agent 并行规格）→ Phase2 Apply → Phase3 Verify（5 agent 对抗，1 must-fix 已修：删用户编辑顶栏说明副标）→ Phase4 真机硬门。
- **真机硬门**（M2101K9C / Android 13）全部关键路径通过 + 文件级证据（5 段命名、manifest scene、改场景重命名）。
- 对比页：新增 [`设计稿与真机对比_v2.html`](设计/设计稿与真机对比_v2.html)（设计稿 ↔ 真机干净默认态整屏，6 对 + 1 新增），资产 `设计/设计稿与真机对比_v2_files/`（源图 `设计稿画廊_v6_files/`）；原 [`归档/compare_design_vs_device.html`](归档/compare_design_vs_device.html) 保留不动。设计稿截法后于「v6·修订」改为**定尺手机框**。
- 红线：屏内零说明性副标；语言中/英；文件名/场景 token 恒英文。

---

## [v5] 主界面三屏对齐 v4 原型 — ✅
提交：`8447d9b`（采集/数据/设置三屏）、`ef588de`（外观主题屏对齐设置G + 红线#1）、`7473396`（prompt）。
- **采集 Tab**：`ModeTile`(WEAR/UNWEAR 切换) → 采集场景 tile；删「写入文件名/自动命名」说明副标；`开始采集` → 沉底「在线采集」+ 上方小入口「离线采集」（占位）。
- **数据 Tab**：删 ALL/Wear/Unwear 模式筛选行，清理 `ModeFilter` 枚举/字段/`data_filter_*` 字符串（无死代码）。
- **设置 Tab**：「外观」分区 → 「通用」（外观主题 + 语言两行，副标显当前值）；新增 `Route.Language` + `LanguageScreen`（中文(简体)/English 单选，**无跟随系统**，`AppPreferences.language` 持久化）；外观屏仍三项。
- 红线：三屏屏内零说明性副标；语言仅中/英；文件名/token 恒英文。Workflow 对抗式验收收口。

---

## [v4] GNSS 线收尾 + 入口迁移 — ✅
提交：`12366b9`、`4fab91e`、`57117b4`(prompt)。
- GNSS 启用从「设置开关」迁到「采集类型勾选」（每会话 + 就地按需授权，写 `SessionConfig.gnssEnabled`）。
- 删除设置页 GNSS A/B/C 屏与入口（裁决 [[gnss-relocation-decision]]）。

---

## [v3] 设计↔实现 首轮逐屏收敛 — ✅
提交：`b7bb0a5`（采集A/用户A/设备A/设置收敛）、`0c97bd7`（env NPE 修复）、`3dd29dd`(prompt)。差异清单见 [`归档/构建笔记/v3_design_diff.md`](归档/构建笔记/v3_design_diff.md)。
- 采集A `EntryTile` 重构（短粗标题+副标+右值/chevron+下方 chip）；用户A 当前/其他分段；圆形 tinted 图标；已连 DUT 图标转绿。
- 裁决：性别照原型用英文枚举 Male/Female/Other（有意偏离 SPEC §7.6，用户口径优先）。

---

## 产品化打磨（M4） — ✅
- `f640f25` 全局外观模式（亮/暗/跟随系统，DataStore 持久、即时生效）。
- `f080eae` 合并系统层 SplashScreen 与应用内 `AppSplash` 为"一个开屏"（冷启一次）。
- 品牌自适应图标 + i18n（zh + values-en + plurals）+ edge-to-edge + 演示钩子 DEBUG 门控。

## 设计原型迭代（仅 `prototypes/v4_android.html`，喂后续实现轮）
- `32af374` 采集场景重设计（主·子场景）+ 采集主界面在线/离线双采集。
- `2911162` 用户选择/编辑重做 + 摘要/详情可改采集人·场景 + GNSS 归不实现。
- `ead637b` 身高/体重改刻度尺（各占一行）+ 摘要/详情改按钮。
- `7d3619b` 设置·语言/外观子页 + 离线采集流程骨架 + 对抗评审收口。
- `495d231` A/B 对比页改白底 + 重截 21 张设计稿。

## 文档 / 基建
- `9fc26d3` 新增 [`里程碑与进度.md`](里程碑与进度.md)。
- `96406ab` 新增设计稿↔真机 A/B 并排对比页（初版）。

## 基线（M1–M3，P1–P4） — ✅
KMP 双模块骨架 + Mock BLE + 扁平设备限额 + 三态采集状态机 + D-6 会话文件夹 + MediaStore 导出 + 本机 GNSS（真机实测真实经纬度）+ 前台服务/进程恢复/存储预检。细节见 [`归档/构建笔记/v1_impl_notes.md`](归档/构建笔记/v1_impl_notes.md)。
