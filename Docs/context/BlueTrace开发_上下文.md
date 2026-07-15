# BlueTrace 开发上下文（最后更新 2026-07-14）

> 给零上下文的下一个会话/接手人看的活文档。真源永远是 [`/SPEC.md`](../../SPEC.md) ＞ [`prototypes/v4_android.html`](../prototypes/v4_android.html)；本文只记"走到哪了、为什么、下一步"。

## 目标与背景

BlueTrace = KMP（Kotlin Multiplatform）Android-first 的 **BLE 生理数据采集 App**：连接 SKG S7 手表（DUT，≤3 台）+ 标准心率带（参考，≤1 台），采集 PPG/ACC/HR（可选 GNSS）→ 会话文件夹落盘（raw HEX 为 source of truth + 解码 CSV + manifest）→ MediaStore 导出，为算法侧提供采集数据。一期只做 Android；iOS（M8）与服务器（M9）二期。

验收习惯（沿用至今的硬门）：`./gradlew :app:assembleDebug` + `:shared:jvmTest` 全绿 → 真机 Xiaomi M2101K9C / Android 13 关键路径跑通并留证据（截图 / adb pull）。

## 当前状态

**已完成（main，2026-06-26 止，已全部推送 origin）**
- M1–M6 全部完成并真机验证：KMP 骨架 + Mock BLE 闭环 → 会话落盘/导出 → 前台服务/进程恢复 → 产品化打磨（启动屏/主题/i18n）→ 设计↔实现逐屏同步至 v6（场景 JSON 真模型、5 段命名、用户选择/编辑重设计、摘要/详情改采集人·场景）→ v7 存储/日志重构（应用日志改滚动 `.log`、用户表迁 SQLDelight 2.3.2）。明细见 [`里程碑与进度.md`](../里程碑与进度.md) 与 [`CHANGELOG.md`](../CHANGELOG.md)。
- 2026-06-25~26 **权限/环境态修复轮**（晚于 MILESTONES 最后更新，那里没记）：门控点"去开启蓝牙"未授 CONNECT 崩溃、开启后状态不刷新、小米软关蓝牙不刷新（改 Flow-first + 回前台 ON_RESUME 兜底）、环境态区分蓝牙"已开启"；V4 原型权限屏重排 + 46 屏设计审查（[`设计审查报告_v6.md`](../设计/设计审查报告_v6.md)）+ 截图画廊。
- V4 原型 37 屏中除"DUT 维护控制台"占位屏外全部实现（main 上）。

**已合并（2026-07-06，`eb47c00` + 远端 PR #2/#3 汇合 `ce6a37a`）：原分支 `feat/s7-device-console`，18 提交**
- 合并时按审查 s7#1 落地 **Mock/真实可切换绑定**：`BleBackendSwitch` 默认真实 GATT，设置页 DEBUG 行「使用 Mock BLE」切换（重启生效）。
- **真实 BLE + S7 手表控制台**：设备维护(DUT)从占位变实功能——扫描/连接页重做（共用过滤条、RSSI 滑块、支持设备置顶、扫描权限门含定位）、控制台头卡断开↔重连、自定义对时（任意时间+时区，测跨时区/过零点）、设备固件日志拉取 → `Download/BlueTrace/logs`（MediaStore）+ 应用内查看页。
- **协议规格文档**：B2A 下行 + zqdata 上行逐字节规格（md+html，含位域）——**2026-07-14 起集中在 [`S7B2A/`](../S7B2A/README.md) 家族目录**（原 `归档/s7/`，那里只留 s7 线 CHANGELOG 与 UHTP 旧稿）。协议知识来源于 apollo4_watch_s7 固件侧分析（E:\1\apollo4_watch_s7 的 Docs/06）。

**阻塞**
- M7（P5 真实 DUT 采集协议解码）：**2026-07-06 口径修正——不是"自研 vs UWTP 二选一"，而是在现网 B2A 主体上添加/扩展采集能力**（UWTP 仅思考方向、可能借用部分设计）；自研 v0.1 线整体归档 [`归档/自研协议线_v0/`](../归档/自研协议线_v0/)，**扩展方案待重新构思**。**采集前置 = OTA**（常规表刷采集固件才能采集）：设计与真实包坐实见 [`OTA/S7采集OTA_设计.md`](../OTA/S7采集OTA_设计.md)（方向 B 实验室 attended，真机可联调，Phase 0 逆向基本完成，下一步 Phase 1 传输地基+Mock）。注意口径：s7 分支已把"真实 BLE 链路"这半边做通（控制台方向），MILESTONES 里"BLE/DUT 仍 Mock"**仅对 main 的采集链路成立**；`BleClient`/`SampleDecoder` 接口隔离已就绪，且 2026-07-06 起注册式协议架构（02 R1–R3）已落码——冻结后只需新增一个 ProtocolProfile 注册进表，编排层零改动。**OTA 工具屏进展（2026-07-09）**：DEBUG「OTA 固件」屏已从单设备扩到多设备——顶栏「多设备」开关（默认关）切两态，开=工作队列**串行**批量（一次一台，一个包多台：连接→读版本电量→电量门槛30%→刷写→复读→断开→下一台，失败/低电跳过可重试）。协议/连接/OTA 会话层零改动。串行编排核下沉 shared `MultiOtaController`（commonMain 无 Android 依赖、iOS 可复用，有 jvmTest `MultiOtaControllerTest` 覆盖串行/失败跳过/电量门槛/重试），`MultiOtaViewModel` 是薄壳 + 多设备 UI，复用 `OtaProvisioner.provisionAndReconnect`/`S7Console`。设计见 [`../OTA/S7多设备OTA_设计.md`](../OTA/S7多设备OTA_设计.md)（4 图），落码明细见 CHANGELOG [OTA·多设备]。**v8 目录树 + OTA 三硬化（2026-07-14）**：公共 `Download/BlueTrace/` 重组为 `log/{ota,firmware,app}` + `config/` + `rawdata/<日期>/`（全英文，旧 `logs/` 有迁移链）；工程配置 JSON（真源私有 `files/config/bluetrace_config.json`，adb push 重启生效，公共侧只镜像不回读）；OTA 执行日志逐行落盘 `log/ota/`（循环模式全量历史）；回连改**扫描优先 ≥60s**（`OtaPhase.Scanning`，配置只可调大）；**手动停止直接发 CTRL_RESET**（`OtaAbort`，跑 app 级 scope 防退屏丢善后）；失败展示结构化（`OtaFailure.describe()`：出错指令/文件+偏移）。明细见 CHANGELOG [存储·OTA]。**真机已验（2026-07-14，Mock 后端）**：树创建/私有+公共迁移（29 devlog 全量迁 log/firmware）/多设备批量+ota 日志落盘/「扫描回连」阶段/rawdata 按日期导出全过，并修 1 个 Main.immediate 时序丢日志头 bug；**真表 D8F7 全过（同日）**：24MB/464s/51.7KB/s 传完→扫描回连 11s 命中；日志增强=落盘行首完整本机时间/逐切片 TRANS 行(只落盘)/失败带百分比/DONE 带用时均速；UI 加 BLE 上传速度（OtaProgress.bytesPerSec）；公共 logs/ 已整目录合并进 log/ 消失。**固件审查坐实（同日，`OTA/OTA中止_固件行为审查.md`）**：传输中固件 OTA 门控（ble2appWrap.c:12021）丢弃一切非 FILE_TRANS 命令——重启指令发了也被吞；真复位=收包看门狗 ≈61s 自复位（SYS_Reset 复位前主动断链）；**中止永不补发 FILE_TRANS_STOP**（删看门狗不清 OTA 标志→设备永久卡死）。App 停止策略已按证据修正（传输中不发 RESET、断开+提示 60s 看门狗；非传输态照发）。**B2A 文档集中 `Docs/S7B2A/`**（归档/s7 只留历史）；**设备指令抽象层设计已出**（`设计/设备指令抽象层_设计.md`，W1–W4 待拍板）；i18n/KMP 审查=`代码审查报告_20260714.md`（Collect.kt MAG/TEMP 泄漏已修；commonMain 零违规但无 iOS target）。剩待验=失败详情卡实景。同日追加：扫描添加表交互修正（用户两轮反馈）——全量显示不折叠、零说明文字；靠排序保证第一屏即手边的表（supported→RSSI 5dBm 分桶→名称）；LazyColumn 去 key（滚动锚点=页面位置，不追设备漂移）；nestedScroll 吃掉列表 overscroll（列表下滑不拖关抽屉，关抽屉=顶部横条/遮罩/返回）。

## 关键决策（节选，全表见 SPEC 与各设计文档）

- KMP 双模块 `:shared`(commonMain 纯 Kotlin) + `:app`(Android)——为二期 iOS 复用数据/协议/会话层。
- Mock↔真实以接口隔离（`BleClient` / `SampleDecoder`），协议未冻结不阻塞上层开发。
- 2026-06-24 用户表选 **SQLDelight 而非 Room**（KMP 原生、iOS 可复用）；2.1.0 与 AGP 9 不兼容 → 定版 2.3.2。偏好（主题/语言/场景/首启）留 DataStore；会话数据留文件 + manifest，不入库。
- 2026-06-24（v6）场景 = `scenes.json` 驱动的主·子二级模型；**5 段文件命名 `主场景_子场景_用户_日期_时间_MAC后四位`，token 恒英文**；manifest 记 mainScene/subScene，事后可改采集人/场景（重写 manifest + 重命名文件夹）。
- demo 阶段存储重构**直接替换不做迁移**（存储与日志设计.md v8 口径）。
- 红线（v5 起）：屏内零说明性副标；语言仅中/英；文件名/token 恒英文。

## 坑与勘误

- SQLDelight 2.1.0 的 Gradle 插件访问 AGP 9 已移除的 `BaseExtension` → codegen 失败，须 ≥2.3.2（issue #5940）。
- 崩溃日志必须 `appendBlocking` 同步落盘（异步 add 在进程被杀前刷不到盘）。
- `AndroidEnvironmentRepository` 字段初始化顺序 NPE：`blocked` 须声明在 `_state` 之前（0c97bd7）。
- 小米 ROM 软关蓝牙不发广播/状态不刷新 → 环境态 Flow-first + 回前台 ON_RESUME 兜底（f277f52）。
- **本机加密盘坑（2026-07-06，根因已确认）**：**C/D 盘 = 端点透明加密盘，E 盘 = 非加密盘；从加密盘直接复制文件到非加密盘不解密，`%TSD-Header-###%` 密文原样落地**（python 在加密盘原地能跑 = 透明解密假象；加密为异步，新文件短暂明文不可侥幸）。Docs 下四份示例脚本曾全部中招。规则（已入全局 skill `win-py-tsd-guard`）：**永不 C/D→E 复制/移动文件**，落仓一律 Write 直写 + 复跑/查头验证；C/D 盘写 Python 不加 `.py` 后缀；见 TSD 头即删除重写。
- JVM 单测里 `JdbcSqliteDriver` 是 JVM-only 工件，测试放 `jvmTest` 而非 `commonTest`。
- 设计遗留缺口（设计审查报告_v6.md 的 ⏳ 项）：暖色"动作 vs 状态"精细分离、主蓝统一 token、Metric/页头模板统一。

## 下一步

0. ~~波次① 数据安全/崩溃~~ ✅（`0c53b25`）、~~波次③ M7 前置接口债~~ ✅（`95b409b`，2026-07-06）：BleClient/SampleDecoder 补形（接口形状已对齐 s7 分支）、背压契约（trySend+droppedPackets 计数、tick 批量刷盘）、Service/Controller 与 Mock 解耦、AppModule 标注唯一切换点。**真机回归已跑**（2026-07-06，M2101K9C：波次①②③+合并+Mock 切换 10 项全过，证据 `../真机证据/regress_20260706/`；未覆盖=真实 S7 手表链路、交互#15 多窗口）。~~波次②~~ ✅（`8480af1`，2026-07-06：勾选导出做真、导出可取消+模态、日志反馈、权限哑弹闭环、暂停语义、空选防呆）。~~波次④~~ ✅（`210da19`，2026-07-06：红→蓝/紫→主蓝、图标盒圆角方+按钮胶囊、长按结束无障碍语义、48dp 触控、LazyColumn 稳定 key，真机抽查生效）。**审查修复线四波收官**；剩余低优先/待条件项清单见 CHANGELOG v10 条目，按需另起小轮。全清单见 [`代码审查报告_20260706.md`](../代码审查报告_20260706.md)。
1. ~~裁决 s7 分支归宿~~ ✅ 已合回 main 并推送；~~真机回归~~ ✅ 已跑（见 CHANGELOG「真机回归」条）。波次④ 新增两小项：DEBUG 演示按钮遮 pill、S7 真表链路待有手表时补测。
2. **推动 `.proto` 冻结解锁 M7**；冻结前可先用标准心率带（HRS 0x180D，不依赖冻结）把真实 BLE 采集链路跑起来。
3. ~~刷新 里程碑与进度.md~~ ✅（2026-07-06 全面刷新：M6.1–M6.4 增量线入册、两处过期口径修正、M7 收窄为"解码半边"、冻结路径=UHTP V1 评审 / 自研 .proto 二选一）。
4. 设计缺口收尾（设计审查报告_v6.md ⏳ 项）。
5. **架构优化线（2026-07-07 分析会话收官，接棒架构演进线）**：通用采集框架化 8 项决策（D1–D8）全部拍板、方向终选 **B-lite 数据核先行**、执行波次 **P0–P5** 已定——定稿见 [`设计/架构优化_通用采集框架化.html`](../设计/架构优化_通用采集框架化.html)，活文档（Decisions/Deviations/Edge Cases/Open Questions）= [`架构优化_实施笔记.md`](架构优化_实施笔记.md)。**下一实操 = P0 传输地基**（characteristicId 透传/写队列/connectionPriority/ScanFilter）。⚠️ 两条要记：D6 拍板 M7 帧内**不加**设备时间戳/seq（已接受风险）；Nordic 替换（旧评估 D2）被本计划建议改为"续用自写 GATT"，待用户确认（笔记 #9）。
6. **架构演进线（评估见 [`architecture/架构评估_20260706.md`](../归档/架构评估_20260706.md)，已扩详版含 §0 机制速览；D1/D2/D3 已拍板：R1–R3 落码 / 传输选 Nordic / CI 已上）**：~~波次A~~ ✅（`3a353ad`：A1 落盘挪 IO 池、A2 异常兜底、A3 Store 自守线程、B1 依赖环消除、CI 上线，首跑绿）。~~波次B~~ ✅（2026-07-06：B2 ConnectionRegistry 事件驱动化+下沉 shared.ble（linkState 断连自动清退）、B3 iOS 债下沉（CollectDraft→shared.domain、toS7Person+readAll→shared.s7、DeviceLogStore 包迁 data.android；**zip 组包有意不下沉**——java.util.zip 是 JVM 专属 API，commonMain 不可用，iOS 接 Apple 压缩 API 时再抽象）、B4 = 02 设计 R1–R3 落码（`shared.protocol.registry` + ProtocolEvent 事件模型 + Mock/HRS Profile + RegistrySampleDecoder，DI 按后端拼注册表；偏离 02 两处见 CHANGELOG）；真机冒烟过（Mock 后端新链路双设备采集 47s 全通）。**接下来 R4**：HRS 真实链路首连——HrsProfile/HrsParser 已就绪，等心率带硬件即可接。

## 相关

- 真源：[`/SPEC.md`](../../SPEC.md)（规格/协议/工程口径）、[`prototypes/v4_android.html`](../prototypes/v4_android.html)（37 屏 UI）
- 进度/变更：[`里程碑与进度.md`](../里程碑与进度.md)、[`CHANGELOG.md`](../CHANGELOG.md)、[`归档/s7/CHANGELOG.md`](../归档/s7/CHANGELOG.md)（s7 协议线，随合并已入 main）。Docs 已三轮深度整理（2026-07-06：s7 五要素/architecture 导航 README/全库死链清零+孤儿截图归档），目录地图见 [`../README.md`](../README.md)（2026-07-07 起 architecture 目录已消灭，架构件在 `归档/`）
- 架构：**architecture/ 目录已随重新构思大归档消灭（2026-07-07）**——架构评估/02 注册式架构设计/scenes.json 旧镜像均入 [`归档/`](../归档/)；02 的 R1–R3 已落码（真相在 `shared.protocol.registry`），R4/R5 要点见「下一步」；scenes.json 运行真源 = `app/src/main/assets/scenes.json`
- 设计验收：[`设计审查报告_v6.md`](../设计/设计审查报告_v6.md)、[`设计稿与真机对比_v2.html`](../设计/设计稿与真机对比_v2.html)
- 测试：`shared`（commonTest/jvmTest 共 108 例，含注册表/连接登记线）、`app/src/test`；真机 M2101K9C / Android 13
- 协议上游：固件侧分析在 `E:\1\apollo4_watch_s7\Docs\06_zqdata服务与上行协议\`；**跨项目共识稿**（B2A + 采集固件 DC/ZQDATA 协议，全字段 file:line 溯源固件代码）：[`S7B2A/S7协议共识规格.md`](../S7B2A/S7协议共识规格.md)（main 上，2026-07-06；采集固件真源 = `E:\1\apollo4_watch_s7_collect`）；**协议新基线 = UWTP V0.99-r2**（V1.0 RC 候选，完整审查 10/10 采纳；**家族独立目录 [`Docs/UWTP/`](../UWTP/README.md)**）：[`UWTP/UWTP_BLE_Protocol_Design_V0.99.md`](../UWTP/UWTP_BLE_Protocol_Design_V0.99.md) + [`UWTP/uwtp_v0.99_draft.proto`](../UWTP/uwtp_v0.99_draft.proto)（2026-07-06，决策 D-1~D-18 + 修订记录 §23 在册；时间戳只属于在线数据、HELLO 带 profile_id/registry_hash 同表校验、OTA 对齐 MCUmgr/SMP + WriteNoRsp 节流）；旧稿 [`归档/s7/protocol-zqdata-uhtp-v1.md`](../归档/s7/protocol-zqdata-uhtp-v1.md)（基于 UHTP V4）**待改写为「S7 采集 Profile」**（UWTP §22 待办 1）
