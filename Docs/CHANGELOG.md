# BlueTrace 修改记录（CHANGELOG）

> 个人仓库、直接推 main（不走 PR）。按"构建轮次"组织（对应 `agent_build_prompt_vN.md`）。
> 真源：`/SPEC.md` ＞ `prototypes/v4_android.html`。BLE 默认真实 GATT（2026-07-06 起，DEBUG 可切 Mock）；采集解码走注册式协议架构（02 设计，HRS 已可解；自研 DUT 协议待 M7 冻结）。高层阶段见 [`里程碑与进度.md`](里程碑与进度.md)。
> 早期 M1–M3 基线细节另见 [`归档/构建笔记/v1_impl_notes.md`](归档/构建笔记/v1_impl_notes.md)、v3 差异见 [`归档/构建笔记/v3_design_diff.md`](归档/构建笔记/v3_design_diff.md)。

---

## [文档] Docs 三轮深度整理：s7 分册五要素 → architecture 导航 → 全库死链清零 — ✅ 2026-07-06
提交 `fd14878` / `955ee7f` / `12f29b7`，同一条整理线的三级递进：
- **s7 子目录**（`fd14878`，s7/CHANGELOG 第 22 轮）：对照协议文档五要素审计——共识稿本达标；补 protocol-zqdata（40B 帧 bit 图 + B2A 封装标尺 + 实例包 decode 脚本实算 + 离线上行状态机）与 protocol-b2a（帧信封 ASCII 标尺 + OTA 状态机图）；plan/review/_raw 十份工作底稿 → `归档/s7协议工作底稿/`。
- **architecture 目录**（`955ee7f`）：新建 [`architecture/README.md`](architecture/README.md)（文档角色/状态清单 + **三线协议关系图**：自研 v0.1+BTCP/1 vs UWTP 二选一待固件评审、S7 现网线与 M7 无关 + 变更纪律）；6 份文档状态行对齐现实（02 已落码/存储已实施/自研线补 UWTP 关系）；v0.proto 死引用修复。**不物理合并**：机器契约路径冻结 + 各文档角色不同，缺的是关系说明不是文件合并。
- **Docs 全库**（`12f29b7`）：脚本全库死链扫描——活区 10 处全修（s7 底稿归档的引用断链），归档历史文档内部死链按冻结原则不动，复扫活区清零；md/html 8 对全同步；`assets/{screenshots,device_v5,device_v6,pic}` 92 文件零活引用 → `归档/历史截图/`（compare 考古页目录常量随迁修复）；代码审查报告/设计审查报告补收官状态行；里程碑 3 处过期口径修正（UHTP→UWTP、解码器口径→注册式架构、D2 Nordic 已拍板）。

---

## [v11·波次B] 架构演进：注册式协议架构 R1–R3 + Registry 事件驱动 + iOS 债下沉 — ✅ 2026-07-06
来源：[`architecture/架构评估_20260706.md`](architecture/架构评估_20260706.md) 波次B（B2/B3/B4）+ [`architecture/02_parser_registry_design.md`](architecture/02_parser_registry_design.md) 迁移节拍 R1–R3。
- **B4 = 02 设计 R1–R3 落码**：新增 `shared.protocol.registry` 包——`ChannelId`/`ChannelParser`/`ProtocolProfile`/`ProtocolRegistry`/`DeviceParserHost`（R1 骨架）+ `ProtocolEvent` 事件模型（Samples/CommandAck/DeviceEvent/Malformed，R3；Capability/State/AlgoResult/FileChunk 四类 payload 依赖 M7 冻结，R5 再补）+ `MockBleProfile`（R2：MockPacketCodec 装进 Profile 形状）+ `HrsProfile`/`HrsParser`（SIG 0x180D/0x2A37，u8/u16 bpm——R4 心率带真实链路的先行协议，不依赖冻结）+ `RegistrySampleDecoder` 适配器。`SampleDecoder` 增 `onDeviceAttached`/`decodeEvents` 默认方法（旧实现零改动）；会话控制器改消费事件流（Malformed→WARN 诊断，raw HEX 照常落盘）+ start 逐设备 attach；DI 注册表按后端拼装（Mock 后端全员 Mock 线协议 / 真实后端注册 HRS；无 profileId 的自研 DUT 回退 Mock→malformed 告警，等价旧 unparseable 行为）。
- **偏离 02 记录**（务实裁剪）：① `BleNotification` 不加 ChannelId 字段——host 按 characteristicUuid 小写匹配 + 单通道 profile 兜底路由（Mock 通知不带特征 id）；② `CommandEncoder` 与控制面事件消费缓行至 R4/R5（采集面现阶段只吃 Notify）。
- **B2 ConnectionRegistry 事件驱动化 + 下沉**：迁 `shared.ble`，构造注入 `BleClient`+scope；add 时启动 linkState 常驻监听，`DISCONNECTED` 自动清退（被动断连不再依赖调用方手动对齐），`RECONNECTING` 在册（琥珀点语义）；监听复用、CAS 防并发重复启动；调用方主动 remove 与自动清退幂等，四个 VM 调用点零改动。
- **B3 iOS 债下沉**：`CollectDraft` 迁 `shared.domain`（零 Android 依赖）；`DeviceLogStore` 包迁 `data.android`（C4 包名归位）；`Subject.toS7Person()` 域映射下沉 `shared.s7`；控制台全量读编排下沉 `S7Console.readAll()` + `S7Snapshot`（单项失败不阻断、失败项 null→上层保留旧值，语义不变）。**zip 组包不下沉**（有意遗留）：`java.util.zip` 是 JVM 专属 API，commonMain 不可用；iOS 侧接 Apple 压缩 API 时再抽象接口。
- 测试：commonTest 新增注册表线 12 例（HrsParser u8/u16/短包、Registry resolve/byId、RegistrySampleDecoder 路由/回退/Malformed/会话边界）+ ConnectionRegistry 迁移并新增事件清退 3 例（被动断连清退/RECONNECTING 在册/清退后重登记复用监听）；`:shared:jvmTest` + `:app:testDebugUnitTest` + `assembleDebug` 全绿；**CI 首跑绿**（D3 闭环，run 28773182921）。
- 真机冒烟（M2101K9C，Mock 后端走新注册表链路）：连 Polar H10+BT-DUT-0427 → 在线采集 47s → 双设备 HR 出值 + ppg_g/ppg_ir/acc 活跃流 → 结束摘要 5358 行 / HEX OK / 解码 CSV 齐全；证据 [`assets/screenshots_device/waveB_20260706/`](assets/screenshots_device/waveB_20260706/)；后端已切回默认真实 GATT。

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
- **新建 [`architecture/README.md`](architecture/README.md)**：文档角色/状态清单 + **三线协议关系图**（自研 v0.1+BTCP/1 与 UWTP V0.99 为 M7 冻结二选一待固件评审；S7 现网线与 M7 无关）+ 变更纪律（状态行强制 / 机器契约路径冻结 / 历史进归档）。
- **全员状态标注对齐现实**：02（R1–R3 已落码 + 两处偏离在册）、03/btcp1、帧规格、`bluetrace_v0.proto`（补 UWTP 二选一关系）、存储与日志设计（已实施 v7）。
- `bluetrace_v0.proto` 修 BlueTrace_Protocol.md 死引用（指向归档路径）；[`README.md`](README.md) 树修正（补评估/architecture README 条目、s7 归档口径、归档桶新增「s7协议工作底稿」）；帧规格 html 重生成。

---

## [文档] s7 协议分册补齐五要素 + 工作底稿归档（第 22 轮） — ✅ 2026-07-06
提交：`fd14878`。对照协议文档标准五要素（Frame 表 / bit-level ASCII 图 / 逐字节 payload / 实例包 decode / 状态机）审计 [`architecture/s7/`](architecture/s7/)：共识稿达标，两份分册补缺——
- **[`protocol-zqdata.md`](architecture/s7/protocol-zqdata.md)**：§3.1 增 40B 帧 bit-level memory map（28B 大端主体 + 12B AGC 尾逐位）；新增 §3.7 B2A 封装上行包字节标尺（小端信封/大端数据分界可视化）、§3.8 **实例包 decode**（HR 起帧 16B 全注解 + 212B 数据包帧0/帧1 逐字段；脚本实算 CRC + 金帧 0x462D 自校验；数值为构造演示值，待采集固件手表实录替换）、§3.9 离线上行发送序列状态机（门槛/流控/截尾/OTA 抢占全 file:line 实证）。
- **[`protocol-b2a.md`](architecture/s7/protocol-b2a.md)**：§2 增帧信封 bit-level 标尺图；§9.1 OTA 状态机扩为 ASCII 图（REQ→READY→START→TRANS→END + 60s 超时/STOP/ERROR 回边 + OFFSET 断点续传）。
- **目录归档**：`plan.md` / `review-report.md` / `_raw/`（10 份工作底稿）→ `归档/s7协议工作底稿/`；保留活文档 command-status（实现追踪）与 completeness-audit（缺口清单）。两份 html 重生成。明细见 [`architecture/s7/CHANGELOG.md`](architecture/s7/CHANGELOG.md) 第 22 轮。

---

## [文档] UWTP 统一可穿戴传输协议 设计 V0.99（冻结候选） — ✅ 2026-07-06
[`UWTP/UWTP_BLE_Protocol_Design_V0.99.{md,html}`](UWTP/UWTP_BLE_Protocol_Design_V0.99.md) + 契约草案 [`UWTP/uwtp_v0.99_draft.proto`](UWTP/uwtp_v0.99_draft.proto)：UHTP V4 的补全审议定稿版（改名 UWTP，D-1~D-14 决策表全记录；**协议家族已归拢至 [`Docs/UWTP/`](UWTP/README.md) 独立目录**，含前身 UHTP V4）。**Core 补齐 V4 六大空白**：GATT 绑定（1 Service + RX WriteNoRsp + TX Notify；S7 复用 ZQDATA 特征、其他项目占位）、时间模型（UTC 系统钟 + u32 秒/u16 ms/s16 时区仅 TIME 域一次 + 数据面只带 ms 偏移的两级时间制）、分域断连语义（OTA 重协商续断点 / FILE 从头重传 / 在线丢了就丢）、并发矩阵（OTA 独占最高优先）、每域丢包容忍度与完整性分层表、安全姿态（Just Works + 预留一次性鉴权）；**统一响应模型**（NEED_RSP 回显 seq + 响应 Protobuf 首字段恒 status）+ 固定字段全小端铁律 + 静态注册表制（不做动态能力发现，文档即共识）。**Domains**：心跳砍掉（BLE 链路监督兜底）；LOG 导出并入 FILE 域（多文件名 + LIST + DELETE，路径隐含约定）；OTA 语义对齐 Zephyr MCUmgr/SMP img_mgmt（槽位/状态位/test-confirm-rollback 映射表）；新增 TUNNEL 透传域。6 组示例帧脚本实算（[`UWTP/assets/gen_uwtp_examples.py`](UWTP/assets/gen_uwtp_examples.py)）。**下一步**：S7 采集 Profile 改写（protocol-zqdata-uhtp-v1 按 D-6/D-8/D-12 删改）→ 固件评审 → 双端金帧联调 → 冻结 V1.0。

---

## [v11·波次A] 架构演进：P0 线程修正 + 依赖环消除 + CI — ✅ 2026-07-06
提交：`3a353ad`（19 文件）。来源：[`architecture/架构评估_20260706.md`](architecture/架构评估_20260706.md)（D1/D2/D3 已拍板：R1–R3 落码 / 传输选 Nordic / 上 CI）。
- **A1 采集落盘挪出 CPU 池**：会话事件循环注入 `Dispatchers.IO.limitedParallelism(1)`（进 IO 弹性池且保持单线程串行语义）；建目录/首写 manifest 一并挪进会话 IO 协程（消架构#4 主线程 IO），init 失败走 ERROR 收尾。
- **A2 全局异常兜底**：应用级 scope 挂 `CoroutineExceptionHandler` → DiagnosticsLog ERROR；logWriter scope 兜底走 logcat（防自写递归）。
- **A3 仓库层自守线程**：`SessionStore`/`DeviceLogStore` 公开方法改 suspend + 内部 `withContext(注入 io)`；删光 7 处调用点散落切换（含 3 处 Composable 内）。
- **B1 依赖环消除**：`MockBleClient`(+Test) 迁 `ble.mock` 子包——`ble↔protocol`、`ble↔s7` 两个包级双向环消失，为 02 设计 R2 铺路。
- **CI（D3）**：`.github/workflows/ci.yml`——push/PR 跑 jvmTest + app 单测 + assembleDebug，失败上传测试报告。
- **文档详版**：架构评估扩写——新增 §0「机制速览」（协程派发器/异常传播/进程生命周期/Compose 状态/ViewModel/DI/KMP 源集/GATT 单飞，8 个机制各配嵌入式类比，面向非 Android 背景读者）+ P0/P1 逐条背景展开 + 拍板记录。
- 验证：构建 + 全部单测绿。**下一波（波次B）**：B2 Registry 事件驱动化+下沉、B3 iOS 债下沉、B4 = 02 设计 R1–R3。

---

## [文档] 架构评估：现状、问题与演进 — ✅ 2026-07-06
[`architecture/架构评估_20260706.{md,html}`](architecture/架构评估_20260706.md)（2 张 SVG：现状分层问题标注图 + M7 目标数据流图）。输入 = 四路代码审查 + 本轮**结构层审计**（模块/包依赖矩阵、KMP 边界、DI、协程纪律逐项核对）。
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
[`architecture/s7/protocol-zqdata-uhtp-v1.{md,html}`](architecture/s7/protocol-zqdata-uhtp-v1.md) + 契约草案 [`zqdata_uhtp_v1_draft.proto`](architecture/s7/zqdata_uhtp_v1_draft.proto)：以 UHTP V4（[`UWTP/UHTP_BLE_Protocol_Design_V4.md`](UWTP/UHTP_BLE_Protocol_Design_V4.md)，5B 头/事务域状态机/Protobuf 协商/Report TLV/offset 传输；已归拢至 `Docs/UWTP/`）为基线的 ZQDATA 重设计。**范围**：离线数据回传（主体，FILE 域深化：目录分页 + 窗口 ACK 授信 + 断点续传 + 整档 CRC32 + 显式删除）、在线数据控制透传（新增 TUNNEL 域，汇顶字节原样进出）、算法结果上传开关（ALGO_CTRL + REPORT_TLV）、个人信息写读（USER_PROFILE）；HELLO 能力协商 + NTP 式对时 + content_format 注册表（现网格式原样回传，推荐迁移 UOF1 统一离线格式）。legacy 共存：0xBB/0x1? 首字节分流 + HELLO 探测回落。示例包 protobuf wire+CRC32 实算（[`assets/gen_zqdata_uhtp_examples.py`](architecture/s7/assets/gen_zqdata_uhtp_examples.py)）。状态：设计稿待固件评审冻结（开放问题 §13）。s7 线明细见 [`architecture/s7/CHANGELOG.md`](architecture/s7/CHANGELOG.md) 第 21 轮。

---

## [真机回归] 波次①②③ + s7 合并 + Mock 切换 全链路 — ✅ 2026-07-06
设备：Xiaomi M2101K9C / Android 13，adb 驱动全程截图取证（[`assets/screenshots_device/regress_20260706/`](assets/screenshots_device/regress_20260706/)）。

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
提交：`eb47c00`（本地合并）+ `ce6a37a`（与远端 PR #2/#3 历史汇合，零内容差异）。18 提交入主线：**真实 BLE**（AndroidBleClient v1）、**S7 手表控制台**（对时/设备信息/写用户/固件日志拉取 → Download/BlueTrace/logs + 查看页）、连接页重做（共用过滤条/RSSI 滑块/支持置顶/扫描权限门）、B2A+zqdata 协议规格随分支带入 `architecture/s7/`（protocol-b2a、protocol-zqdata、子 CHANGELOG）。
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
[`architecture/s7/S7协议共识规格.{md,html}`](architecture/s7/S7协议共识规格.md)：跨项目共识稿（固件双仓 ↔ BlueTrace），**全部字段出自固件代码**并标注 file:line——`E:\1\apollo4_watch_s7`（开发仓，B2A 基线）+ `E:\1\apollo4_watch_s7_collect`（采集仓）。覆盖：GATT 服务地图（AMDTP FFE0 / GH3X2X 190E ↔ ZQDATA 45121540 互斥切换 `svcSwFlag[0]`、开发仓新 zqdata 模块 0x0A10 段）；B2A 信封逐字节/逐位（0xBB 头 + 命令头 + CRC16-CCITT-FALSE + 多包）；**采集仓新增协议**：DC 采集会话（TEST 0x10/0x11/0x12，IDLE/COLL/READY/TRANS 状态机 + EOF 次序保证）、`ecg_raw.dat` v2 文件格式（32B 头 + 8B 帧头流 + ACC 段回填）、ZQDATA 上行（40B→28B 重打包、HR/SpO2 偏移差异、18B gsensor、212/240B 包核算、228B 上限、svcSwFlag[6] 互斥）；7 组示例包 decode（含真机产测金帧 0x462D + 实算帧，脚本 [`architecture/s7/assets/gen_s7_protocol_examples.py`](architecture/s7/assets/gen_s7_protocol_examples.py) 带金帧自校验）；错误处理汇总 + App 侧实现共识 + 双固件差异表（依据两仓 merge-base `c6f87d36` 的 git diff 实证）。4 张 SVG + ASCII 位图。深度参考指向 s7 分支 protocol-b2a/zqdata（未合 main）。

---

## [文档] BLE 协议规格开发者版（帧布局/位图/实例包） — ✅ 2026-07-06
[`architecture/BLE协议帧规格_开发者版.{md,html}`](architecture/BLE协议帧规格_开发者版.md)：把 SPEC §4 + `bluetrace_v0.proto` 重排成实现视角——12B 帧头逐字节/逐位布局（表 + ASCII + 5 张 SVG）、TLV（T=msgType/L=payloadLen/V=protobuf）注册表、4 个**实算并自回验**的示例包 decode（HighFreqBatch 逐字节、Command→一包双帧 Ack+DeviceState、DeviceCapability 分片×3 重组、Mock 格式）、解帧/重组/控制闭环/pktSeq resync 状态机、错误处理汇总。生成脚本存档 [`architecture/assets/gen_frame_examples.py`](architecture/assets/gen_frame_examples.py)（protobuf wire 编码 + CRC-8 poly 0x07/init 0x00 MSB-first 实算，反射约定标注为待冻结项）。**内容零新增规格**，冲突时以 SPEC §4 / `.proto` 为准。

---

## [v7] 存储 / 日志重构 — ✅ 2026-06-24
提交：`f38ce01`（日志 → 滚动 .log）、`2c248a3`（用户 → SQLDelight 用户表）。**本地 main 未推送**。
设计文档：[`architecture/存储与日志设计.md`](architecture/存储与日志设计.md)（v8 审查口径 #1–#13）。demo 阶段**直接替换、不做迁移**。Workflow 编排 + 真机 `adb pull` 取证。

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
- **涉及**：6 张 `assets/screenshots_v6/design_*.png` + 对比页副标/脚注 2 行文案改「定尺渲染」；页面 CSS 原样（中途误改已撤回）。

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
- 对比页：新增 [`设计稿与真机对比_v2.html`](设计/设计稿与真机对比_v2.html)（设计稿 ↔ 真机干净默认态整屏，6 对 + 1 新增），资产 `assets/screenshots_v6/`；原 [`归档/compare_design_vs_device.html`](归档/compare_design_vs_device.html) 保留不动。设计稿截法后于「v6·修订」改为**定尺手机框**。
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
