# BlueTrace 合并规格（SPEC）

> **一句话定位**：BlueTrace 是一款 Android 优先（KMP-ready，iOS 紧随）的 BLE 多设备生理/运动数据采集 App——以「原始 HEX 行日志为唯一权威源」的方式，把自研 DUT（私有 TLV+protobuf 协议）与标准心率带（SIG HRS 0x180D）的数据，连同本机 GNSS，落成「每会话一文件夹」的可重放数据集。
>
> **两份主线文件**（开发与审阅只需这两份）：
> ① **本规格 `SPEC.md`** —— 做什么 / 怎么定 / 协议契约 / 工程口径（自足、可执行）。
> ② **`Docs/prototypes/v4_android.html`** —— UI 与逐屏交互的**单一真源**（含每屏 `.screen-ux` 交互规格块，37 屏全覆盖）。本规格不复述像素与组件，屏级细节一律指向原型对应屏。
> *iOS 主线原型待补*（X 系列里程碑产出，见 §7.4）。机器可读消息契约见 `Docs/architecture/bluetrace_v0.proto`。
>
> **口径基准**：以 **V4 + 2026-06-17 最新口径**为准；文档间历史矛盾已就地消解，旧文档归档 `Docs/legacy/`（见 §9）。

## 目录

1. [概览与范围](#1-概览与范围)
2. [需求要点（F-* / 追踪矩阵）](#2-需求要点)
3. [关键决策与口径（D-* / D-V4-*）](#3-关键决策与口径)
4. [通信协议（帧 / 分片 / protobuf）](#4-通信协议)
5. [交互行为规格（状态机 / 导航 / 标签 / 数据流 / 异常 / 权限）](#5-交互行为规格)
6. [数据与文件模型（D-6 / manifest / 导出 / GNSS）](#6-数据与文件模型)
7. [工程执行要点（minSdk / 三 Tab 脚手架 / 对齐锚点 / iOS 分叉）](#7-工程执行要点)
8. [设计系统要点（token 以原型为准）](#8-设计系统要点)
9. [引用与归档](#9-引用与归档)
10. [开发就绪（KMP · Android-first）](#10-开发就绪kmp--android-first)

---

## 1. 概览与范围

**产品形态**：底部三 Tab（**采集 / 数据 / 设置**）为唯一主信息架构（IA）。采集是强状态任务链；数据 / 设置为旁支中枢。

**核心数据流**：App 作纯 BLE Central 观测者 —— 订阅 Notify → 帧拼接 → 分片重组 → 按 `msgType` dispatch 解码 → 落 `raw/*.hexlog`（source of truth）+ 解码 CSV + manifest。App **不主动配置任何 BLE 链路参数**（MTU / 连接间隔由设备按功耗自定，D-5），只解包观测。

**设备模型**：
- **DUT（自研设备）≤ 3 台**：走私有协议（TLV 信封 + protobuf）；第 3 起链路标黄、第 4 台禁用。
- **REFERENCE（标准心率带）≤ 1 台**：标准 SIG HRS `0x180D`，不走私有协议、不解帧/不分片，仅参考心率源。
- **本机 GNSS**：一期正式一路数据源（不进 BLE 角色体系，独立 `gps.csv`）。

**一期工程形态**（D-1/D-2）：Android 原生先行，纯逻辑层按 **KMP-ready** 写（commonMain 无 Android 依赖、可 JVM 单测）；iOS 不并入一期、紧随其后从 commonMain 平移。

**一期范围（保留 / 延后 / 移除）**：

| 类别 | 内容 |
| --- | --- |
| ✅ 一期保留 | 三 Tab IA；扁平设备列表（DUT≤3 + 参考≤1）；纯开关传感器配置；三态采集运行；D-6 会话文件夹 + manifest；MediaStore 导出；用户(Subject)实体；Wear/Unwear 模式；**GNSS（F-GPS-1 升为正式功能）** |
| 🔵 二期延后 | 在线采集/实时透传/服务器上传/远程下发/send log；「在线 vs 离线」双 CTA；打印报告（用导出替代）；暗色模式；`CMD_SET_PACKING` 固件调试控制（默认隐藏）；波形⇄分包双视图(F-COL-2/3)、只读观测面板(F-CTRL-4)、运行中控制(F-CTRL-5)、采样率(F-CTRL-2) |
| ❌ 移除/改名 | 「离线收集」双 CTA → 单一「开始采集」；打包策略配置页（D-5）；带宽硬闸（D-9）；竞品命名 ACQ/Creek（「ACQ Project」→「采集模式」）；DUT/REFERENCE 角色槽 hub → 扁平列表 |

---

## 2. 需求要点

> 当前**全部需求状态 = ⬜ 未开始**（项目处于设计阶段，仅文档与原型，暂无源码）。F-* 条款已精炼内联本节；原始条款 + §9 追踪矩阵见归档 `Docs/legacy/REQUIREMENTS.md`（历史参考）。

### 2.1 权限与环境
- **F-PERM-1（P0）**：检查蓝牙开关、BLE 扫描/连接权限、定位（按平台），区分硬性/建议条件。
- **F-PERM-2（P0）**：缺硬性条件阻断采集并给修复入口；建议条件（通知/定位）可继续。
- **F-PERM-3（P1）**：系统权限弹窗前先展示用途说明层（iOS 上架硬要求）。

### 2.2 设备接入
- **F-DEV-1（P0）**：按 Service UUID 过滤扫描 DUT/REFERENCE，显示名称、RSSI、MAC。
- **F-DEV-2（P0）**：扁平设备列表，单击连接/断开；DUT ≤3（第 4 禁止）+ 至多 1 个标准心率带参考（HRS 0x180D）；无角色槽，采集单设备优先。
- **F-DEV-3（P0）**：至少一个有效设备连接成功才允许开始采集。
- **F-DEV-4（P1）**：记住上次设备，恢复时优先用已知地址定向重扫（MAC 不可靠，需 Service UUID 兜底）。

### 2.3 设备配置与控制
- **F-CTRL-1（P0）**：列出 DUT 传感器，逐路**纯开关**（开＝该路是否采集/上传，透明传输）；不外露高/低频、不外露采样率。
- **F-CTRL-3（P1）**：选择 DUT 设备端算法（HR/SpO₂/RR/计步/跌倒等），结果随流回传（依赖真机协议）。
- F-CTRL-2/4/5 已降「后期」，一期不做（见 §3 偏离项）。

### 2.4 采集与可视化
- **F-COL-1（P0）**：数据采集页：设备卡（含 HR）+ 采集类型选择（旧称"勾选"，见 D-V4-12）+ Datas/Time 简单实时数据区 + 标签（Pin + Start/Stop，见 D-V4-14）。
- **F-COL-4（P0）**：显示已采时长、已存行数、当前文件大小。
- **F-COL-5（P2）**：采集中可打时间标签 → 细化为 **Pin 瞬时点 + Start/Stop 区间**（D-V4-14）。
- F-COL-2/3（波形⇄分包流双视图）已降「后期」。

### 2.5 会话与文件
- **F-FILE-1（P0）**：每会话一个文件夹（同时仅一个任务）：解码后每路独立 CSV + 会话级 manifest（起点时间、对齐锚点、设备控制配置）。
- **F-FILE-2（P0）**：采集结束展示摘要（时长、设备、各路文件与行数、异常）。
- **F-FILE-3（P1）**：历史会话列表与详情，展示传感器组合。
- **F-FILE-4（P0）**：导出到公共目录（Android Downloads / iOS Files & 共享）。
- **F-FILE-5（P1）**：删除会话（前置确认）。
- **F-FILE-6（P0）**：原始抓包日志（主体·无损）：每来源一份 `<epochMs>: HEX` 行日志，append-only、行级崩溃安全、可重放；后期加流式压缩（zstd）。
- **F-FILE-7（P1）**：组合包兼容 CSV（汇顶 PPG+ACC 等）生成专用合并 CSV；输出映射按消息类型 1→N 文件。

### 2.6 后台、异常与恢复
- **F-BG-1（P0）**：后台/锁屏持续采集（平台各自机制）。
- **F-BG-2（P0）**：退出采集需明确确认（长按 2 秒防误触）。
- **F-BG-3（P0）**：异常精简为三态——采集中 / 暂停·重连（断→自动重连续写）/ 已停止——+ 运行日志；无法处理的错误记日志并丢弃，不分级、不打断。
- **F-BG-4（P1）**：进程被杀后回 App 可逐角色恢复或引导重扫。
- **F-BG-5（P1）**：采集以前台服务模型常驻；常驻通知；被回收靠 `START_STICKY` + 自启动/电池白名单引导（国产 ROM）自恢复；仅用户长按结束才停止。**不依赖背景定位权限。**

### 2.7 数据源扩展 · 用户与模式
- **F-GPS-1（P1，一期实现）**：本机 GPS 一路数据源（经纬度/海拔/速度/精度/时间戳），与 BLE 多路共用同一会话、同一 monotonic 锚点、独立 CSV；**while-in-use 定位足够**，不申请 background-location。
- **F-SUBJ-1（P0）**：用户实体（别名/性别/生日/身高/体重），本地存储、可多条、可选当前；采集前在采集 Tab 选择。
- **F-SUBJ-2（P0）**：用户写入文件名前缀与会话 manifest；建议用别名而非真实姓名、一期不上传（隐私）。
- **F-MODE-1（P0）**：采集模式 Wear/Unwear（会话级），运行头只读显示，写入文件名前缀与 manifest，数据 Tab 可按模式筛选。

### 2.8 Phase 归属
| Phase | 内容 |
| --- | --- |
| **P1** 权限/扫描骨架 | F-PERM-1/2/3、F-DEV-1 |
| **P2** 设备/开关/状态机 | F-DEV-2/3、F-CTRL-1、F-COL-1/4、F-BG-2/3（含 F-SUBJ-1、F-MODE-1 的 P2 部分） |
| **P3** 持久化/文件/导出 | F-FILE-1/2/3/4/5/6/7、F-SUBJ-2、**F-GPS-1（一期实现，挂 P3）** |
| **P4** 后台/恢复 | F-BG-1/4/5、F-DEV-4 |
| **P5** 固件联调 | F-CTRL-3 |

---

## 3. 关键决策与口径

### 3.1 工程与协议决策（D-1..D-10，已闭环 2026-06-15）

| # | 结论 |
| --- | --- |
| D-1 | Android 原生先行，纯逻辑层 KMP-ready（无 Android 依赖、可 JVM 单测）。 |
| D-2 | iOS 不并入一期，紧随其后（从 X0 起平移进 commonMain）。 |
| D-3 | 保留定位、**不声明 `neverForLocation`**（GPS 是真实数据源）；续采靠前台服务 `location` 类型，**不申请 `ACCESS_BACKGROUND_LOCATION`**。 |
| D-4 | DUT 通信协议 = 外层 TLV 信封 + 内层 protobuf，双向（数据流 + 控制/ACK），含 sequence/时间戳、类型注册表可扩展；我方设计交付物（v0.1 草案已过评审）。 |
| D-5 | **全部链路参数（连接间隔 + MTU）由设备按功耗驱动，App 完全不参与、只解包**；删除打包策略配置页。 |
| D-6 | 每会话一文件夹 = 原始 HEX 行日志（source of truth，后期 zstd）+ 按模块解码 CSV + 组合包兼容 CSV + `manifest.json`；Sink 放宽为「1 消息类型 → 1..N 文件」。 |
| D-7 | Android **minSdk 29 / target 35**；iOS 15+。简化红利：纯 MediaStore 导出、`java.time` 原生可用（去 desugaring）。*（target 后由 D-V4-19 跟进 36）* |
| D-8 | 混合编码（高频批包紧凑 `bytes`/`packed`、低频/控制结构化 protobuf）+ 标准头部支持分片/重组（大消息超 MTU 跨包，按 `msgId+fragIdx` 组包）。 |
| D-9 | 控制面留可选 `CMD_SET_PACKING`（仅固件调试）；**去掉「带宽超阈值阻止开始」硬闸**（V4 进一步将观测面降后期）。 |
| D-10 | Wire + `.proto` 单一事实源；固件自研（同团队），`.proto` 内部共管、双端同一 schema，无外部依赖。 |

### 3.2 V4 设计决策（D-V4-1..16，2026-06-16）

| # | 结论 |
| --- | --- |
| D-V4-1 | 底部三 Tab（采集/数据/设置）为唯一主 IA；采集子页与运行隐藏 Tab 防误切。 |
| D-V4-2 | 主界面一页放下、字阶收紧（标题 19 / 卡标题 14 / 三级字重 700-600-400）。 |
| D-V4-3 | 设备改**扁平列表**（取代角色槽 hub）；单击连、再点断；**DUT ≤3**（第 3 起标黄、第 4 禁用）**+ 参考心率带 ≤1**（HRS 0x180D 对照）。 |
| D-V4-4 | 传感器配置 = **纯开关**（去采样率、去高/低频标注）；透明传输，开关只决定该路要不要上传；设备端算法→后期。 |
| D-V4-5 | 异常压成**三态 + 运行日志**：采集中 / 暂停·重连（断连自动重连续写）/ 已停止（不可继续才停）；坏包/CRC/解码失败/未知消息/协议不识别/重连**一律进运行日志并丢弃**，不打断、不分级。 |
| D-V4-6 | 权限 = 全局硬门：开始采集前一次性门控；要 GNSS 就开始前授权，否则本次会话不含 GPS 一路；否定「单路缺权限降级」。 |
| D-V4-7 | 采集运行照竞品 Data Collection 重做：设备卡（含 HR）+ 采集类型勾选 + Datas/Time 实时数据区 + Start/End 标签 + 暂停 + 长按 2 秒结束；**去波形⇄分包双视图、只读观测面板、运行中控制面板**。 |
| D-V4-8 | **受试者 → 用户**：UI 一律称「用户」；工程文档实体仍可用 Subject。 |
| D-V4-9 | 离线收集 CTA 移除；数据 = D-6 会话文件夹；一期单一「开始采集」；数据 Tab 列会话文件夹，非扁平 rawdata。 |
| D-V4-10 | **原型为 UI 单一真源**；实现文档不复述界面，只讲「怎么搭」，改 UI 只动原型。 |
| D-V4-11 | **「暂停」= 仅停数据框滚动显示，不停接收/采集**；底层照常落盘、再点恢复跟随；文字保留「暂停」，语义在注释/文案点明。 |
| D-V4-12 | 采集类型选择：改名 +采集中不隐藏 + 开关=是否上传；下抽屉 Bottom Sheet；进入运行自动弹出，右上角/采集中可重开；圆形勾选保留。 |
| D-V4-13 | 结束按钮蓝「结束」（正常收尾，数据已存）+ 长按 2 秒确认半透明遮罩（透出内容、松手取消）。 |
| D-V4-14 | 标签区：内嵌 X 清空 + Pin 瞬时点 + Start/Stop 区间。 |
| D-V4-15 | 实时流 = `[时间戳] HEX` 每条一行 · 锚定底部 · 无滚动条（等宽小字号；justify-end + overflow hidden；数据框 flex 填充）。 |
| D-V4-16 | 配置两屏（传感器总控/设备端算法）暂不实现、移文档末尾；原型升级为 **UI + 交互双真源**（`.screen-ux` 37 屏全覆盖）。 |
| D-V4-17 | **导航按最新 Android / Material 3 落地**（2026-06-17）：`NavigationSuiteScaffold` 自适应（Bar→Rail→Drawer）+ 类型安全 Navigation Compose（`@Serializable` 路由）+ 每 Tab 独立返回栈/重选回根 + 预测返回（采集运行用 `PredictiveBackHandler` 拦截）+ edge-to-edge（targetSdk 36）。详见 §7.2 + 导航链路图。 |
| D-V4-18 | **异常模型再精简 + 硬锁定 + 前台服务恢复**（2026-06-17 共识）：断联交 SDK 自动重连（内联「重连中」·不弹屏不阻断）；无法处理的进**全局诊断日志**（不入会话夹）；存储满**自动结束**（开始前预检 + <1GB 启动提示 + 不足禁开 + toast）；采集运行**硬锁定**（app 内不可退，在场感 = 前台服务通知）；进程被杀→数据已落盘，重启**服务活则重绑续采 / 全杀则开口会话自动收尾**（`stopReason=interrupted` + toast，无恢复弹层）；时间全用 **unix 时间戳**，manifest 记时区 + 起点 + 质量小结。横切状态组屏暂不实现。 |
| D-V4-19 | **技术栈选型（KMP · Android-first，2026-06-17 拍板）**：KMP 结构（`:shared` commonMain 共享协议/会话/CSV/manifest/repo 接口 + `:app` Android）；BLE = androidMain Nordic Kotlin-BLE 藏在 commonMain 接口后（iOS 后期 Core Bluetooth）；protobuf = Wire（高频批包走手写字节解析）；文件 IO = okio；序列化 = kotlinx.serialization；DI = Koin；导航 = Navigation Compose 类型安全；prefs = DataStore；**SDK target/compile 跟进发布 = 36**（minSdk 29 不变）。详见 §10。 |

### 3.3 真源分工

**主线两份**：`本 SPEC（做什么 / 协议 / 工程口径）` + `v4_android.html 原型（长什么样 + 逐屏交互 + 跨屏流转）`；外加 `bluetrace_v0.proto` 机器契约。原型从「视觉真源」升级为「视觉 + 交互」真源。决策的「为什么」可溯源至归档的设计契约 / 决策追踪（`Docs/legacy/`），但**结论已内联本规格 §3，无需再读归档**。

### 3.4 相对旧需求基线的偏离（已在本规格落定，供复核）

> 下列点曾与旧 REQUIREMENTS / PRD 措辞不一致；**现一律以本规格为准**（旧基线已归档 `Docs/legacy/`，不再反向回灌）。

- F-COL-2/3、F-CTRL-2/4/5：因 D-V4-7 重做而降后期 / 移出运行界面。
- F-COL-5：由「Start/End Label」细化为 Pin 瞬时点 + Start/Stop 区间（D-V4-14）。
- 「暂停」语义 = 仅停数据框滚动显示、不停接收 / 采集（D-V4-11）。
- 采集类型选择：改名 + 采集中不隐藏 + 开关=是否上传（D-V4-12）。

---

## 4. 通信协议

> **适用范围**：仅 **DUT（自研设备）** 与 App 之间的私有协议。REFERENCE（标准心率带）走 SIG HRS `0x180D`，**不使用本协议**。
> **机器可读契约（单一事实源，D-10）**：`Docs/architecture/bluetrace_v0.proto`。帧头/分片/CRC 链路事实已内联本节 §4.3–4.9（原始来源已归档 `Docs/legacy/BlueTrace_Protocol.md`）。
> **状态**：v0.1 草案，待与固件端共同冻结。

### 4.1 协议基本盘

| 项 | 内容 |
| --- | --- |
| 文档版本 | v0.1（草案） |
| 协议头 `ver` | `0x00`（冻结为 v1 时再定值） |
| 字节序 | **小端 little-endian**（待固件确认） |
| 传输 | BLE GATT，App 作 Central；设备→App 走 Notify，App→设备 走 Write |
| payload 编码 | protobuf（proto3，Wire 生成）；高频样本走紧凑 `bytes` |
| 解码层 | KMP commonMain，纯 Kotlin、无 Android 依赖 |

**分层模型**（自上而下）：强类型样本 `SensorSample/Flow<T>` ← Wire 解码 ← protobuf payload(V) ← 按 msgType 分发 + 组包还原 ← 标准头部(ver+T+长度+分片+CRC) ← BLE GATT notify/write（MTU 由设备定）。

> **一个帧 = 12B 头部 + payloadLen 字节 payload；一个 BLE 包内可含 1..N 个非分片帧；一条大消息拆成多个分片帧跨多包。**

### 4.2 BLE 链路层（GATT）

| 角色 | Characteristic | 方向 | 属性 |
| --- | --- | --- | --- |
| 数据流 | `DUT_NOTIFY_CHAR` | 设备→App | Notify |
| 控制 | `DUT_WRITE_CHAR` | App→设备 | Write / WriteWithoutResponse |

- UUID 占位在架构的 `DutUuids`，冻结时替换；Notify（非 Indicate）。
- **App 不主动调链路参数**：连接间隔、MTU 全由设备按功耗申请（D-5）；App 只订阅 Notify、解帧、解码——只解包观测、不配链路参数。
- **写方向（App→设备）v0 不做协议级分片**：每条 `Command` 必须装进单次 ATT write（受 `maximumWriteValueLength` 约束），固件收到的是 GATT 层重组后的单个完整 ATT value，不含分片头；可靠性靠 `cmd_id`+`Ack` 闭环，不使用 `pktSeq`/`NEEDS_ACK`。

### 4.3 标准帧头（固定 12 字节，小端）

| 偏移 | size | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | `ver` | 协议版本，v0=`0x00`；不匹配则停止解析该包 |
| 1 | 1 | `msgType` | 消息类型，见注册表 |
| 2 | 1 | `flags` | 位标志，见下 |
| 3 | 1 | `fragIndex` | 分片序号 0..fragCount-1（非分片=0） |
| 4 | 1 | `fragCount` | 分片总数（非分片=1） |
| 5 | 1 | `hdrCrc8` | **强制**：头部 CRC8，覆盖头部除本字节外的 11 字节 |
| 6 | 2 | `pktSeq` | **包序号**（小端 uint16），每个 BLE notification +1，模 2^16 回绕；同包内多帧共享相同 pktSeq |
| 8 | 2 | `msgId` | 逻辑消息 ID（小端 uint16），同一消息的分片共享，用于组包 |
| 10 | 2 | `payloadLen` | 本帧 payload 字节数（小端 uint16） |

**flags 位定义**：

| bit | 名称 | 含义 |
| --- | --- | --- |
| 0 | `MORE_FRAGMENTS` | 还有后续分片（最后一片为 0） |
| 1 | `FRAGMENTED` | 本帧属于一条分片消息 |
| 2 | `NEEDS_ACK` | 需对端回 ACK（保留；v0 控制走 cmd_id/Ack） |
| 3 | `BATCH` | 1=高频批包 / 0=低频组帧（语义提示，可选） |
| 4 | `HAS_PAYLOAD_CRC` | payload 末尾 2 字节为 CRC16 |
| 5–7 | 保留 | 置 0 |

> `fragIndex/fragCount` 为 uint8 → 单消息最多 255 片。12B 头对高频批包（一包几十~几百样本）是可忽略开销。

### 4.4 分片与重组（分包/组包）

- **定位**：分片仅给**偶发大消息**（如 `DeviceCapability`）用，**不给稳态高频批包**。高频应通过 `SetPacking` 把单批控制到 ≤ 单包可用空间，避免分片抬高延迟。
- **触发**：payload 超过 `MTU − 12`（带 payload CRC 再 −2）时切成 N 片。
- **分片规则**：所有分片共享同一 `msgId`，携带相同 `msgType`/`fragCount`，`fragIndex=0..N-1`，`FRAGMENTED=1`；前 N-1 片 `MORE_FRAGMENTS=1`，末片为 0；**一个分片独占一个 BLE 包**（不与其它帧拼接）。
- **App 侧重组**：按 `msgId` 缓冲，集齐 `fragCount` 片后按 `fragIndex` 升序拼接 payload 再交 protobuf 解码。
- **消歧规则**：同 msgId 且 `(msgType,fragCount)` 一致 → 归入当前消息（**乱序允许**，重复片忽略）；不一致或收到 `fragIndex==0` 起始片 → 判为新一轮（丢旧缓冲重开）；缺片超时（建议 **2s**）→ 丢弃整条计丢包，不阻塞后续；`fragIndex≥fragCount` → 丢弃记日志；`msgId` 复用前须留足超时间隔。

### 4.5 一包多帧（拼接）规则

一个 notification 可含多个**非分片**帧：`[hdr|payload][hdr|payload]...` 按 `payloadLen` 顺序切分至包尾。
- 强制循环：读 12B 头 → 校验 `hdrCrc8` → 读 `payloadLen` 字节 → 下一帧。
- **框架错误处理**：剩余字节 <12（且 >0）→ 丢尾部残余计错、保留前序帧；`payloadLen` 超过剩余 → 丢该帧起余下计错、保留前序帧；`hdrCrc8` 失败 → 丢该帧起余下计错、保留前序帧。
- **分片帧（FRAGMENTED=1）必须独占整包**，不参与拼接。

### 4.6 消息类型注册表（header `msgType`）

`msgType` 决定 payload 用哪个 protobuf message 解（dispatch 表），protobuf 内不再包 envelope。

| msgType | 名称 | 方向 | message | 说明 |
| --- | --- | --- | --- | --- |
| `0x01` | HIGH_FREQ_BATCH | 设备→App | `HighFreqBatch` | PPG/ECG/IMU/ACC 高频批包 |
| `0x02` | LOW_FREQ_FRAME | 设备→App | `LowFreqFrame` | 地磁/温度低频组帧 |
| `0x03` | GOODIX_PPG_ACC | 设备→App | `GoodixPpgAcc` | 汇顶 PPG+ACC 组合包（→兼容 CSV F-FILE-7） |
| `0x10` | DEVICE_EVENT | 设备→App | `DeviceEvent` | 设备事件/告警 |
| `0x11` | DEVICE_CAPABILITY | 设备→App | `DeviceCapability` | 静态能力：传感器集+采样率+算法 |
| `0x12` | DEVICE_STATE | 设备→App | `DeviceState` | 当前状态：启用项/生效率/活跃算法（重连对账） |
| `0x20` | ALGO_RESULT | 设备→App | `AlgoResult` | 设备端算法结果 |
| `0x40` | COMMAND | App→设备 | `Command` | 控制指令 |
| `0x80` | ACK | 设备→App | `Ack` | 指令回执 |
| `0x04–0x0F` | 保留·数据 | | | 新增传感器消息 |
| `0x41–0x7F` | 保留·控制 | | | |
| `0xC0–0xFF` | 厂商/实验 | | | 私有扩展 |

> **未知/保留 msgType 强制处理**：接收方遇到 dispatch 表外的 msgType，**必须用 `payloadLen` 跳过该帧 payload 并继续解析本包余帧**（计数诊断），绝不中止整包——这是「加传感器不破坏旧端」的前提。

### 4.7 payload 编码：高频批包 / 低频组帧

**高频批包**（`HighFreqBatch` / `GoodixPpgAcc`）：protobuf 头（sensorId / 设备时间戳 / 采样周期 / count / 通道数 / 格式 / 批序号）+ 一个 `bytes` 字段装**紧凑定宽样本数组**，避免逐样本 protobuf tag 开销。

`samples` 字节布局（**行主序、小端、紧密无填充**）：
```
sample[0]ch[0], sample[0]ch[1], ..., sample[0]ch[C-1],
sample[1]ch[0], ...                共 sample_count * channel_count 个值
```

每值字节宽度（**强制表**）：

| SampleFormat | 字节 | 解码 |
| --- | --- | --- |
| SF_U16 / SF_S16 | 2 | 小端；S16 二补码 |
| SF_U24 / SF_S24 | 3 | 小端；**S24 须按 bit23 符号扩展**：`v=b0|b1<<8|b2<<16; signed=(v^0x800000)-0x800000`（`0xFFFFFF→-1`）；U24 零扩展 |
| SF_S32 / SF_F32 | 4 | 小端；F32=IEEE754 |

- **长度校验（强制）**：`samples.length` 必须 == `sample_count * channel_count * width(format)`，否则**丢弃该批并计错误**（不向上抛异常打断管线）。`SF_UNSPECIFIED` 与未知 format → 该批按解码错误处理。
- **第 i 样本设备时间** = `base_device_ts_us + round(i * sample_period_ns / 1000)`（用纳秒周期而非整数 Hz，避免 12.5Hz 这类小数率累积漂移）。

**低频组帧 / 控制 / 事件 / 能力 / 状态**：常规结构化 protobuf message；低频标量数组用 `packed` repeated。config 速率统一**毫赫兹** `*_mhz`（支持小数率），高频计时用 `sample_period_ns`。

### 4.8 双向控制与 ACK

App 下发 `COMMAND`（带 `cmd_id`），设备执行后回 `ACK(cmd_id, status)`。覆盖：开关传感器、采样率、设备端算法、应用层打包旋钮、会话启停、能力查询、当前状态查询。**重连/进程恢复**：App 重连后发 `QueryState` 读回设备**实际生效配置**（含被夹紧值），与本地 manifest/UI 对账，不靠「自己上次发了什么」猜。

### 4.9 完整性与丢包

- **头部框架自检（强制）**：`hdrCrc8` = CRC-8（poly `0x07`、init `0x00`）覆盖头部 11 字节（偏移 0–4、6–11，跳过自身）。
- **payload 完整性（可选）**：`HAS_PAYLOAD_CRC=1` 时 payload 末 2 字节为 CRC16/CCITT，protobuf 实际长度 = `payloadLen-2`。BLE 链路层已对空口位错做 CRC（控制器内消费，App 读不到），故 payload CRC 仅端到端排错时开启。
- **丢包检测**：`pktSeq`（**包级**，统计所有 notification，缺口=BLE 包丢失）；`HighFreqBatch.batch_seq`（**流级**，按 sensor_id，缺口=该传感器批丢失）；两者初值 0，(re)subscribe 后首包建基线（首包不计丢）；缺口判定模运算 `gap=(cur-prev)&0xFFFF`，超阈值（建议 `0x8000`）视为重连 resync；`pktSeq` 每连接重置。

### 4.10 .proto 关键消息结构（精炼，以 `bluetrace_v0.proto` 为准）

```protobuf
syntax = "proto3";
package bluetrace.protocol.v0;

// === 数据：高频批包 (msgType 0x01) ===
message HighFreqBatch {
  uint32 sensor_id         = 1;  // 见 SensorId
  uint64 base_device_ts_us = 2;  // 首样本设备单调时钟(us)
  uint32 sample_period_ns  = 3;  // 每样本间隔(ns)=精确计时源
  uint32 sample_count      = 4;
  uint32 channel_count     = 5;  // ACC=3、PPG=1..N
  SampleFormat format      = 6;  // 不得为 SF_UNSPECIFIED
  uint32 batch_seq         = 7;  // 流级批序号, 从0起, 模2^32
  bytes  samples           = 8;  // 长度必须 == count*channel*width(format)
}
enum SampleFormat {            // 宽度: U16/S16=2B  U24/S24=3B  S32/F32=4B
  SF_UNSPECIFIED = 0;          // 非法
  SF_U16 = 1; SF_S16 = 2; SF_U24 = 3; SF_S24 = 4; SF_S32 = 5; SF_F32 = 6;
}

// === 数据：低频组帧 (msgType 0x02) ===
message LowFreqFrame {
  uint64 device_ts_us = 1;
  repeated LowFreqReading readings = 2;
}
message LowFreqReading {
  uint32 sensor_id = 1;
  repeated sint32 values = 2 [packed = true];  // 如地磁 xyz、温度
  sint32 scale_milli = 3;  // 真实值 = value*scale_milli/1000; ==0 表示不缩放
}

// === 数据：汇顶 PPG+ACC 组合包 (msgType 0x03) ===
message GoodixPpgAcc {
  uint64 base_device_ts_us = 1;
  uint32 ppg_sample_period_ns = 2; uint32 ppg_sample_count = 3;
  uint32 ppg_channel_count = 4;    SampleFormat ppg_format = 5;
  bytes  ppg = 6;                              // PPG 流(独立 period/count/channel/format)
  uint32 acc_sample_period_ns = 7; uint32 acc_sample_count = 8;
  uint32 acc_channel_count = 9;    SampleFormat acc_format = 10;
  bytes  acc = 11;                             // ACC 流(典型 3 通道 xyz)
}

// === 数据：事件 / 能力 / 状态 / 算法 ===
message DeviceEvent { uint32 code = 1; string detail = 2; uint64 device_ts_us = 3; }   // 0x10

message DeviceCapability {                                                              // 0x11 静态能力
  string firmware_version = 1;
  repeated SensorCap sensors = 2;
  repeated uint32 algorithm_ids = 3 [packed = true];
}
message SensorCap {
  uint32 sensor_id = 1; bool high_freq = 2;
  repeated uint32 supported_rates_mhz = 3 [packed = true];  // 毫赫兹(12500=12.5Hz)
  uint32 channel_count = 4;
}

message DeviceState {                                                                   // 0x12 当前状态(对账)
  repeated SensorState sensors = 1;
  repeated uint32 active_algorithm_ids = 2 [packed = true];
}
message SensorState { uint32 sensor_id = 1; bool enabled = 2; uint32 current_rate_mhz = 3; }  // 含夹紧后真实值

message AlgoResult {                                                                    // 0x20 算法结果
  uint32 algorithm_id = 1; uint64 device_ts_us = 2;
  repeated AlgoMetric metrics = 3;
}
message AlgoMetric { uint32 metric_id = 1; float value = 2; }  // HR/SpO2/RR…

// === 控制：App→设备 (msgType 0x40) === (须装进单次 ATT write, 写向不分片)
message Command {
  uint32 cmd_id = 1;            // 自增, ACK 回显
  oneof body {
    SetSensorEnable set_sensor_enable = 2;
    SetSampleRate   set_sample_rate   = 3;
    SelectAlgorithm select_algorithm  = 4;
    SetPacking      set_packing       = 5;  // F-CTRL-4 打包旋钮(仅固件调试,默认隐藏)
    SessionControl  session_control   = 6;
    QueryCapability query_capability  = 7;
    QueryState      query_state       = 8;  // 请求 DeviceState
  }
}
message SetSensorEnable { uint32 sensor_id = 1; bool enabled = 2; }
message SetSampleRate   { uint32 sensor_id = 1; uint32 sample_rate_mhz = 2; }  // 毫赫兹
message SelectAlgorithm { repeated uint32 algorithm_ids = 1 [packed = true]; }
message SetPacking      { uint32 batch_samples = 1; uint32 frame_period_ms = 2; }
message SessionControl  { enum Action { STOP = 0; START = 1; } Action action = 1; }
message QueryCapability {}
message QueryState {}

// === 回执：设备→App (msgType 0x80) ===
message Ack { uint32 cmd_id = 1; uint32 status = 2; string detail = 3; }  // status 0=OK

// === 公共枚举（待与固件冻结）===
enum SensorId {
  SENSOR_UNSPECIFIED = 0;
  SENSOR_PPG = 1; SENSOR_ECG = 2; SENSOR_ACC = 3;
  SENSOR_GYRO = 4; SENSOR_MAG = 5; SENSOR_TEMP = 6;
}
```

---

## 5. 交互行为规格

> **跨屏交互行为契约**。屏级像素/组件/逐屏细节一律指向 `v4_android.html` 对应屏的 `.screen-ux` 块，本节不复述。

### 5.1 页面地图与导航规则

**主 IA = 底部三 Tab（采集 / 数据 / 设置）**，三者平行切换。v3 向导式 Home 已降 legacy。导航**组件策略 / NavGraph 树 / 返回栈 / 预测返回**的完整落地见 **§7.2 导航架构（Material 3 Adaptive）+ 导航链路图**；本节只列交互行为规则。

- **Tab 可见性**：仅三个顶级中枢显示底部 Tab；**进入任一子页（push 带返回）或采集运行（全屏）一律隐藏 Tab**，避免强状态任务被误切走。
- **采集是向前 push 的强状态任务链**：采集 Tab →（设备 / 用户 / 模式）→ 采集类型 → 运行 → 摘要，全程隐藏 Tab。
- **在场感 = 前台服务常驻通知**（非 app 内 Banner）：退后台后服务续采，点通知回运行页。
- **采集运行硬锁定**：App 内**不允许退出**运行界面（返回键/手势拦截，提示"长按结束退出"，不切 Tab / 不 push）。OS Home 退后台 → 前台服务续采、再进 app 直接落运行界面。**唯一退出 = 长按结束 2 秒**。
- **跳转一致性**：会话卡单击→会话详情（隐藏 Tab）；用户行单击=即时选中并返回采集页（无确认按钮）；子页均 push + 返回。

**启动落地**：**启动屏一闪而过** + 静默环境检查（蓝牙开关 / 权限 / 可恢复会话）→
- **首次启动** → 权限请求门控页（无返回，可「暂时跳过」）→ 采集 Tab；
- **后续启动** → 静默检查：全 OK 直接进采集 Tab；仅当缺权限，进采集 Tab 后弹轻量 Sheet（可跳过）；
- 检测到可恢复会话 → 叠加进程恢复弹层（见 §5.10）。

→ 屏级细节：v4_android.html「启动A 启动屏 / 启动B 首启请求 / 启动C 后续缺权限弹出」+「采集 / 数据 / 设置 Tab」。

### 5.2 权限与蓝牙开关（D-V4-6 全局硬门 · 2026-06-17 细化）

**权限分级**
- **硬性权限**（BLE 扫描 / 连接）：采集必需。门控**可跳过**进主界面，但触发扫描 / 开始采集时**就地拦截**、直接弹该权限的系统请求。
- **建议权限**（通知、定位 / GNSS）：缺**不阻断**。通知缺 → 后台采集更易被系统回收（提示但继续）；定位缺 → 本次会话不含 GNSS 一路。

**请求时机**
- 首启：门控页一次性请求（无返回，可「暂时跳过」）。
- 后续启动：静默检查，仅缺项才弹轻量 Sheet（可跳过）；不再走门控页。
- 按需：跳过后到用时再就地请求该具体权限。

**权限不足处理**
- 系统弹窗可弹 → 授予即继续原操作。
- 用户「永久拒绝」（系统不再弹）→ 引导**去应用系统设置页**开启；返回后自动复检回到原操作。
- 运行时被撤销 → 回到对应拦截态。

**蓝牙开关（≠ 权限，单独检测）**
- 蓝牙是系统开关，监听 adapter 状态而非申请权限。
- 进扫描 / 开始采集前检测：关 → 拦截 + 「开启蓝牙」（拉起系统启用对话框 / 引导快捷开关）；开启后自动复检。
- **采集进行中**蓝牙关闭 → 走 §5.4 横切A 暂停 · 自动重连（不丢数据）。

**后台保活（电池优化 · 系统设置 ≠ 权限，F-BG-5）**
- 采集需长时间后台运行；门控「建议」组提供入口，引导用户把本应用切到「不省电 / 不受限」：忽略电池优化（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 拉系统 allowlist 对话框）+ 国产 ROM 自启动 / 关联启动 / 最近任务锁定（各家路径不同，跳系统设置）。
- **建议不阻断**：不设置也能采集，只是长时间后台更易被系统回收；配合前台服务 + `START_STICKY` 自恢复 + 进程恢复弹层（§5.10）兜底。

**存储空间（开始前门控 · 2026-06-17 共识）**
- **不足不允许开始**：开始前预检可用空间；不足时「开始采集」拦截 → toast 提示去清理。
- **低空间启动提示**：可用 < **1 GB** 时启动给一次提示（不阻断，建议清理/导出）。
- **采集中写满 → 自动结束**（见 §5.4），不要求用户处理。

**应用日志（运行错误/事件 · 非开发调试日志）**：记录**运行期的出错与关键事件** —— 连接/通信、解析（CRC/解码/未知 msgType）、存储、导出、权限、（二期）服务器访问等；写 app 私有的**全局滚动日志**，跨会话一份、**不进会话文件夹**、滚动上限自动截断；供现场排错 / 报问题，**不是 app 的开发调试日志（logcat/verbose）**。**设置 → 应用日志**（设置E）查看 / **导出**（写 `Download/BlueTrace/logs/` 或系统分享）/ 清空。

**设备维护（DUT · 后期）**：设置 → 设备维护（设置F）是预留入口，承载设备端能力——设备对时（同步 unix 时间到 DUT）/ 写设备用户信息 / 读取固件日志 / OTA 固件升级；**入口预留、具体内容后期**（对时/用户信息/固件日志走协议 Command·Ack §4；OTA 属二期），需连接 DUT 才启用。

设置 Tab 提供「环境与权限检查」手动复查入口（带返回）。

→ 屏级细节：v4_android.html「启动B/C/D 权限」「启动E 蓝牙已关闭」「启动F 后台省电设置指南」「设置A 环境与权限检查」「GNSS A/B/C」。

### 5.3 设备连接规则

- **扁平列表，单击切换**：单击设备行 → 后台连接（未连接 → 连接中[动画点] → 已连接）；再点 → 断开。返回不断开（连接后台保持）。
- **连接上限**：DUT ≤3（达 3 顶部黄条提示链路不稳定；**第 4 台行禁用不可点**）+ **参考心率带 ≤1**（紫 · HRS 0x180D 对照，超 1 个禁用）；断开后恢复可连。
- 右上「已连 N」实时计数（DUT + 参考心率带合计）；至少一台连接成功才允许开始采集，采集单设备优先。
- 过滤：搜索按名称 / MAC；RSSI 滑条仅显示强于阈值的设备。
- 扫描 **60s 自停**；底部「停止扫描」可手动停。范围内 60s 无 DUT → 空态引导（区别于权限缺失）。

→ 屏级细节：v4_android.html「设备A 设备列表 / 设备B 连接上限 / 设备C 扫描无结果」。

### 5.4 采集运行状态机（精简 · 2026-06-17 共识）

不再设独立"暂停·重连"屏；异常压到最薄——专业采集核心是把数据收回来：

| 情况 | 处理 |
| --- | --- |
| **采集中** | 计时（unix 墙钟）持续走；每收到一条写一行 `[unix] HEX` 并落盘 |
| **断联**（蓝牙关 / 走远 / 通信错） | **交给 BLE SDK / 协议栈自动重连**，App 不弹屏、不阻断、无需操作；设备卡内联「重连中」（琥珀点）；重连成功 → 原始 HEX append-only **续写续解析**；断联期是数据空档（时间戳体现），计时照走 |
| **存储满**（唯一被迫停） | **自动结束**并安全落盘（HEX + CSV + manifest 完整）+ toast「存储满，已停止并保存」（开始前预检见 §5.2） |
| **结束**（任何时候，含断联/重连中） | 长按 2 秒 → 收尾落盘 → 结束摘要；可立即开下一轮 |

**硬锁定**：采集中 App 内**不允许退出运行界面**（返回键/手势拦截，提示"长按结束退出"，不切 Tab / 不 push）。OS Home / 手势挡不住 → 退后台后**前台服务继续采集**，**前台服务常驻通知**即"正在采集"的在场感（点通知回运行页）；再进 app 直接落运行界面，**无 app 内 Banner**。

**底部「暂停」按钮** = 仅停数据框滚动显示，数据照常接收/落盘，再点恢复跟随（与"断联"无关）。

**无法处理的 → 全局诊断日志**：坏包 / CRC 错 / 解码失败 / 未知 msgType / 重连事件 → 写**全局滚动诊断日志**（app 私有、跨会话一份，**不进会话文件夹**）；不弹分级 Banner、不打断采集。原始 HEX 始终在会话文件夹、可重放；会话级质量小结（重连次数 / 断联时长 / 丢包数）写 manifest（§6.2）。

→ 屏级细节：v4_android.html「运行A 就绪 / 运行B 采集中」（断联 = 设备卡内联「重连中」）。横切状态组屏已精简、暂不实现。

### 5.5 标签（Pin 瞬时 + Start/Stop 区间，D-V4-14）

- **Pin** = 打瞬时点标签（单次写入一条标签行）。
- **Start Label ⇄ Stop Label** = 打长时间区间标签：点 Start 开区间，按钮切为 Stop，再点闭合区间；标签事件写入实时流（如 `▶ LABEL START · "rest baseline"`）并落盘。
- 输入框有文字 → 内嵌 **X** 清空（取代独立清除键）。

→ 屏级细节：v4_android.html「运行B 采集中」标签区。

### 5.6 实时数据流（D-V4-15）

- **每收到一条 → 写一行 `[时间戳] HEX`**（如 `[02:13.92] 7E 02 1A 08 …`）。
- 窗口**锚定底部、无滚动条**，旧行自顶部裁切（`.log-list.tail`）。
- 顶部计数：`Datas`（累计行数）+ `Time`（已采时长）。
- 「暂停」仅冻结此流的滚动显示，不影响接收与落盘。

→ 屏级细节：v4_android.html「运行B 采集中」实时流区。

### 5.7 采集类型选择（D-V4-12）

- 进入运行 **自动弹出**下抽屉（Bottom Sheet）；右上 ⚙ / 采集中均可重开（**采集中不隐藏入口、仍可重选**）。
- 勾选要采集/上传的路 →「确定」写回；「取消」不改动；点遮罩 / 下滑关闭。
- 开关 = 该路是否上传(需通信)/落盘（**透明传输、不控采样率**）。
- 注：传感器总控（配置A）/ 设备端算法（配置B）均标注「暂不实现 / 后期」，选择能力已并入此处。

→ 屏级细节：v4_android.html「运行C 采集类型选择」。

### 5.8 结束与导出（长按 2 秒唯一退出，D-V4-13）

- 按住「结束」（蓝主色，正常收尾、数据已存）→ 半透明遮罩（下面内容透出可见、不整屏遮挡），环形进度走满 **2 秒** → 结束、落盘 → 结束摘要；**中途松手即取消**，继续采集（防误触）。
- 结束即落盘；一个会话 = 一个文件夹（D-6）：原始 HEX 日志（source of truth）+ 每模块解码 CSV + 组合包 CSV + manifest。
- 导出整夹到 `Download/BlueTrace/`（MediaStore，无需存储权限）；导出中可取消，完成 Toast 反馈。

→ 屏级细节：v4_android.html「运行D 长按结束 / 结束A 摘要 / 结束B·导出B Toast / 导出A 进度」。

### 5.9 空 / 错 / 过渡态

| 态 | 表现 / 行为 | 对应屏 |
| --- | --- | --- |
| 数据空态 | 顶级 Tab 无返回、保留底部 Tab；首次落盘后出现首个会话文件夹 | 数据A |
| 搜索无结果 | 输入实时过滤无命中 → 空结果态；清除关键词回完整列表 | 数据D |
| 设备扫描无结果 | 60s 无 DUT → 空态「重新扫描」 | 设备C |
| 用户空态 | 无档案 → 引导「+ 新建用户」 | 用户B |
| 导出失败 / 写入中断 | 红色阻断 Banner + 行内重试；原始数据不受影响 | 导出C |
| 存储不足（导出前预检） | 红色阻断「去清理空间」→ 设置存储占用删旧会话 | 导出D |
| 多选 / 选择态 | 长按 / 点「选择」进入（隐藏 Tab）；标题随「已选 N」变化；底部导出所选 / 删除（二次确认）；取消退出 | 数据B |
| 会话详情 | 隐藏 Tab；manifest + 启用配置只读 + 文件夹构成逐项可勾选导出 | 数据C |

→ 屏级细节：v4_android.html 各对应屏的 UX 交互。

### 5.10 进程恢复（无对话框 · 2026-06-17 共识）

前台服务是「会话 + BLE」的持有者，UI 只是连上它的薄客户端：
- **服务仍活着（仅 UI/Activity 被回收）** → 重启 app **重新绑定服务** → 直接回采集运行界面，会话无缝继续、零丢失、**无弹层**。
- **进程整体被杀（app + 服务都没）** → 无活会话；重启时扫到一个**没写结束标记的"开口"会话** → **自动收尾**（`endEpochMs` = 最后一条记录时间、`stopReason=interrupted`），变成数据 Tab 一条正常会话 + **toast「上次采集异常中断，已保存」**；用户开新的即可。
- **数据安全**：原始 HEX append-only + 及时 flush，被杀最坏丢"最后未刷盘的 <1s / 最后半行"（重启截到最后换行）；manifest 关键信息（时区/起点/用户/模式/设备配置）**开始时即写** → 会话自描述。

去掉 v3「逐角色 DUT 续采 / REFERENCE 重扫」恢复弹层（横切D 暂不实现）。

---

## 6. 数据与文件模型

### 6.1 D-6 会话文件夹模型（原始 HEX 为 source of truth）

一次会话产出一个独立目录，整体导出/上传更方便。**原始 HEX 行日志是唯一权威数据**，所有 CSV 都是显示/分析派生物——即便解码逻辑或 schema 日后变更，也能从 HEX 回放重解。

```text
files/sessions/
  Wear_shb_20260521_153000_de54/        // 一会话一文件夹，名 = 模式_用户_时间_设备
    raw/  dut.hexlog  reference.hexlog   // 原始 HEX 行日志（<epochMs>: HEX，可重放，后期 zstd）
    csv/  ppg.csv ecg.csv imu.csv mag.csv // 解码后按模块分 CSV
    csv/  ppg_acc.csv                     // 组合包兼容 CSV（如汇顶 PPG+ACC，1 包→N 文件）
    gps.csv                              // 本机 GNSS（开启 F-GPS-1 时）
    session_manifest.json
```

四类产物：① **原始 HEX 行日志**（主体，`<epochMs>: HEX` 行格式，可重放）；② **每模块解码 CSV**（ppg/ecg/imu/mag…）；③ **组合包兼容 CSV**（一个组合包拆成多文件，对齐竞品格式）；④ **manifest**；GNSS 单独 `gps.csv`。

> 文件命名：`<Mode>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>`。
> 旧目录口径（`session_YYYYMMDD_HHmmss/` + 每角色一 `DUT_<MAC>.csv` + 滚动 `.csv.1`）是 D-6 之前的历史口径，**已作废**；一律以上述 `raw/`+`csv/` 文件夹模型为准。

### 6.2 manifest 字段（session_manifest.json）

**开始采集即写关键信息**（会话自描述、被杀也能解）：
- `sessionId`、`startEpochMs`（unix 毫秒·起点）、**`timezone`**（如 `Asia/Shanghai` / `+08:00`）、`endEpochMs`（结束 / 自动收尾时写）。
- **`subject`（用户，原"受试者"）**：别名 / 性别 / 生日 / 身高 / 体重。
- **`mode`**：Wear / Unwear。
- sampling config（启用的传感器 / 设备端算法；**不含 BLE 链路参数**，D-5）。
- device roles（每角色：`role` / `address` / `name` / `csvFiles[]` / `profileId` 如 `"HeartRate.SIG.0x180D"`）。
- file list、app version、permission / GNSS 快照、`stopReason`（`normal` / `storage_full` / `interrupted`）。
- **会话质量小结**：`reconnectCount`、`disconnectTotalMs`、`droppedPackets`（按 `pktSeq`/`batch_seq` 缺口估）—— 供离线判断数据完整度，与全局诊断日志是两回事。

### 6.3 时间模型（unix 时间戳为准 · 2026-06-17 共识）

- **所有记录时间 = unix epoch**：原始 HEX 行 `<epochMs>: HEX`、解码 CSV 每行带 `epoch_ms` 列、计时 / 时长全用 unix；**不存设备本地字符串时间**。
- **manifest 记时区 + 起点**：`timezone` + `startEpochMs`，离线还原本地时间用（见 §6.2）。
- **断联期间计时照走**（unix 墙钟）；数据空档由相邻记录的时间戳间隔体现。
- 多台设备的样本都由**同一台手机**打 unix 戳（接收时刻 `receivedAtEpochMs`），天然同一时间轴；对齐误差 ≈ BLE 通知到达时延差（典型 < 50 ms，对 PPG 评测足够），**对齐留给离线分析**（写入端不做对齐）。
- *（可选安全网）* 可同时存 `monotonicNanos`(`System.nanoTime`) 以防会话中途墙钟被改 / NITZ 跳变；离线若发现 unix 跳变可改用单调差。非必需。

### 6.4 导出走 MediaStore 公共目录

- **minSdk = 29 / targetSdk = compileSdk = 36**（D-V4-19 跟进发布；原 D-7 为 35）。
- 导出**统一走 MediaStore**，落到公共 `Download/BlueTrace/`，**无需任何存储运行时权限**。导出对象为整个 D-6 会话文件夹（打包后写入）。
- 实现要点：`MediaStore.Downloads.getContentUri(VOLUME_EXTERNAL_PRIMARY)` → `ContentValues` 设 `DISPLAY_NAME` / `MIME_TYPE` / `RELATIVE_PATH = Download/BlueTrace` / `IS_PENDING=1` → `insert` 拿 uri → `openOutputStream` 写入 → 清 `IS_PENDING=0` `update`。SAF 的 `ActivityResultLauncher` 不入侵 ViewModel/Repository。
- **作废分支**：API 24–28 直接复制 + `WRITE_EXTERNAL_STORAGE(maxSdk≤28)` 降级、`coreLibraryDesugaring`（java.time）——minSdk 29 起 `java.time.*` 原生可用，全部移除。
- 私有外部目录 `getExternalFilesDir(...)` 作主存储免权限；公共 Downloads 仅导出时用。给用户展示用户可见路径（如 `Downloads/BlueTrace/session_...zip`），不暴露私有目录路径。

### 6.5 GNSS（F-GPS-1，一期正式功能）

- 本机 GPS 作为**一路数据源**写入会话独立 `gps.csv`（不进 BLE 角色体系），与 BLE 多路共用同一会话、同一 monotonic 锚点。
- **while-in-use 定位**足够（不申请 background-location，D-3）。
- 前台服务类型新增 `location`：`foregroundServiceType="connectedDevice|location|dataSync"`（`location` 因 F-GPS-1 保留，`dataSync` 属二期上传可收敛）。
- 注：BLE 扫描的定位依赖（未声明 `neverForLocation` → 需 `ACCESS_FINE_LOCATION`）与 GNSS 的定位用途是两条独立需求，但同走定位权限。

---

## 7. 工程执行要点

### 7.1 SDK / 平台
- **Android**：minSdk 29 / target 36 / compile 36（D-V4-19 跟进发布，原 D-7 为 35）。简化红利：纯 MediaStore 导出、`java.time` 原生可用（去 desugaring）；删 `WRITE_EXTERNAL_STORAGE(≤28)` 分支。
- **iOS**：15+（紧随一期，见 §7.4）。

### 7.2 导航架构（最新 Android · Material 3 Adaptive）

> 唯一主 IA = 底部三 Tab（采集 / 数据 / 设置）。本节按 2024–2025 官方推荐落地：Material 3 自适应导航 + 类型安全 Navigation Compose + 预测返回 + edge-to-edge。导航链路全图见随附「导航链路图」。

**① 组件策略**
- **`NavigationSuiteScaffold`**（`material3-adaptive-navigation-suite`）统一承载顶级导航，按 `WindowSizeClass` 自适应：Compact（手机竖屏，一期主形态）→ **底部 NavigationBar**（3 项，M3 药丸选中指示 + 活动态填充图标）；Medium（折叠展开/平板竖屏）→ **NavigationRail**；Expanded（平板横屏）→ **NavigationDrawer**。一期只需保证 Compact 正确，Suite 让 Rail/Drawer 零成本未来兼容。
- **类型安全 Navigation Compose**（2.8+）：路由用 `@Serializable` object/class，编译期检查、带参传递；不用字符串路由。
- **每个顶级 Tab = 一个独立嵌套 NavGraph + 独立返回栈**；切 Tab 保留各自栈与滚动位置（`saveState`/`restoreState`），**重选当前 Tab → 弹回该 Tab 根**（`popUpTo(rootGraph){saveState=true}; launchSingleTop=true; restoreState=true`）。
- **预测返回（Predictive Back）**：`enableOnBackInvokedCallback=true`；跨屏用系统预测返回动画；采集运行用 `PredictiveBackHandler` 拦截（见④）。
- **Edge-to-edge**（targetSdk 35+ 强制）：内容绘到系统栏后；NavigationBar 消费底部 inset，列表/CTA 用 `WindowInsets` 留安全区。
- 模块边界：`ui/`（Screen 渲染状态 + 收集操作）、`viewmodel/`、`domain/`、`service/`、`worker/`。

**② 导航图（NavGraph 树）**

```text
RootNavHost
├─ Splash（启动屏·一闪而过，无导航 UI）
├─ PermissionGate（首启请求·无返回；非首启不入，仅缺权限弹 ModalSheet）
└─ MainScaffold（NavigationSuiteScaffold）
   ├─[Tab] CollectGraph（采集，start）
   │   ├─ CollectHome（顶级·显示 Bar）
   │   ├─ DeviceConnect / SubjectSelect→SubjectEdit / SensorConfig(暂不)（子页·隐藏 Bar）
   │   └─ CollectionRun（全屏·隐藏 Bar·硬锁定）
   │        ├─[Sheet] CollectTypeSheet · LongPressEnd
   │        └─ SessionSummary → Export
   ├─[Tab] DataGraph（数据）
   │   ├─ DataHome（顶级）│ DataSelect（多选）│ SessionDetail → Export 态（子页·隐藏 Bar）
   └─[Tab] SettingsGraph（设置）
       └─ SettingsHome（顶级）│ EnvPermissionCheck / GnssSource / ExportLocation / Storage / AppLog(运行日志·导出) / DeviceMaintenance(DUT·后期) / PowerSaveGuide / About（子页·隐藏 Bar）

系统/启动机制（非目的地·非 app 内覆盖层）：在场感 = 前台服务常驻通知（点回运行）· 进程恢复 = 服务重绑续采 / 开口会话自动收尾（无对话框，见 §5.10）
```

**③ Bar 可见性 / 返回栈**：底部 Bar **仅在 3 个顶级目的地**（CollectHome / DataHome / SettingsHome）显示——通过观察 `currentBackStackEntry` 的 destination 判定；进任何子页 / 采集运行 → 隐藏。每 Tab 独立返回栈、切 Tab 保状态、重选回根（见①）。

**④ 采集运行硬锁定 + 预测返回**：采集运行全屏，App 内**不允许退出**。`PredictiveBackHandler` 拦截返回手势 → 不退出、给"长按结束退出"提示（不切 Tab / 不 push）。OS Home 退后台 → 前台服务续采，**前台服务常驻通知**作在场感（点回运行页）、再进 app 直接落运行界面；**无 app 内 Banner**。**唯一退出 = 长按结束 2 秒**（防误触）。

**⑤ 深链 / 进程恢复 / 状态保存**：`rememberSaveable` + ViewModel(`SavedStateHandle`) 保各 Tab 滚动/输入；进程被杀重启 → Splash 静默检查检测到活跃/可恢复会话 → 直接路由 `CollectionRun` 或弹 `ProcessRecoverySheet`（横切D）。Sheet（采集类型 / 长按结束 / 进程恢复）用 `ModalBottomSheet`，dismiss 即关、不进主返回栈。首启标记用 `AppPreferences`（DataStore）持久化。

**⑥ iOS 对位**（一句）：SwiftUI 用 `TabView`（底部）+ 每 Tab 一个 `NavigationStack` + `fullScreenCover` 承载采集运行；Bar 可见性 / 硬锁定 / 状态保存 行为与 Android 一致（逻辑层在 commonMain 共享，见 §7.5）。

### 7.3 领域层契约
- **SubjectRepository**：domain 层，用户（原受试者）本地 CRUD，V4 一期功能；配套屏 `SubjectSelectScreen` / `SubjectEditScreen`（别名/性别/生日/身高/体重）。实体 `Subject { id, alias, sex, birthMonth, heightCm, weightKg, note? }`，本地存储、可多条、可选当前；隐私=建议用别名、不上传不出设备。
- **SessionConfig**：`SessionController.start(assignment: DeviceAssignment, config: SessionConfig)` 入参。`DeviceAssignmentDraft` 仅开始采集前可编辑，点 Start 后冻结为正式 `DeviceAssignment`。**Fake Controller 是真实实现的接口替身（非临时 mock）**，先用它跑通整条导航与三态切换。
- **采集运行三态**（异常模型）：采集中 / 暂停·重连 / 已停止，外加不分级的运行日志（坏包/CRC/解码失败进日志不打断，HEX 仍落盘可重放）。

### 7.4 前台服务（后台采集）
- BLE 采集主链路由前台服务 `connectedDevice` 类型承载（Android 14+ 强制声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 运行时 `BLUETOOTH_CONNECT`）；FGS 必须由前台用户动作触发，避免 `ForegroundServiceStartNotAllowedException`。
- 常驻通知；被回收靠 `START_STICKY` + 自启动/电池白名单引导（国产 ROM）自恢复；仅用户长按结束才停止（F-BG-5）。

### 7.5 iOS 后续分叉点
技术路线：**KMP 共享领域层（commonMain：协议解析/打包/会话状态机/CSV 行格式/manifest 模型）+ 各端原生 BLE/UI**（Compose / SwiftUI）。主要分叉：
- **BLE 栈**：iOS 用 Core Bluetooth（`CBCentralManager`/`CBPeripheral` + delegate），**不暴露 MAC**，靠系统 `identifier`(UUID)，跨重启可能变 → 设备恢复持久化「identifier + Service UUID 兜底重扫」。
- **后台采集（最大分叉）**：iOS 无前台服务/WakeLock；靠 Background Modes `bluetooth-central` + **State Preservation & Restoration**（`CBCentralManagerOptionRestoreIdentifierKey` + `willRestoreState`）做进程恢复；后台扫描必须带 Service UUID 且被节流，通知不能保活（仅系统蓝条提示）。
- **权限**：iOS 蓝牙扫描**不需定位权限**，用 Info.plist 一次性用途文案（`NSBluetoothAlwaysUsageDescription`）。
- **文件导出**：写沙盒 `Documents/` + `UIDocumentPicker`/分享 Sheet，用户经 **Files App** 访问（需 `LSSupportsOpeningDocumentsInPlace`）；CSV 行格式与目录命名两端共享。
- **里程碑 X0–X4**：先抽 commonMain 领域层（JVM 单测）→ Android actual → iOS Core Bluetooth actual → iOS 后台+State Restoration → iOS Files 导出 + 双端一致性走查。

### 7.6 国际化（i18n）—— 从第一行代码就做，禁止后期补

> 一期 UI 文案为中文，但**从一开始就走资源化**，避免到后期再返工抠字符串。原型 `v4_android.html` 的中文是 **zh-CN 参考文案**，落地时映射到字符串资源键。

- **严禁硬编码用户可见文案**：Android 走 `strings.xml`；KMP 共享文案放 commonMain（moko-resources 或 expect/actual provider），Compose/SwiftUI 不写死中文。
- **带参数 / 复数用模板与 plurals**，不靠字符串拼接（语序随语言变）；如「已连 N」「已选 N」「N 行」用占位符。
- **locale 目录结构先建好**：一期只填 zh，预留 `values-en/` 等；新增语言只加资源、不改代码。
- **机器口径不本地化**：协议字段（`*_mhz`/`*_us`/`*_ns`、HEX）、文件夹/文件名时间戳（固定 `yyyyMMdd_HHmmss`）保持 locale 无关；仅 UI 展示用 locale 格式化数字/日期/时间。
- **布局抗文案膨胀**：英文/长词不裁切（呼应 §8.3 动态字号）；方向用 `start/end` 而非 `left/right`，为 RTL 留空间（一期不做 RTL）。
- 单位与符号（HR ♥、Wear/Unwear 等枚举）走资源，便于后续按语言调整显示名。

---

## 8. 设计系统要点

> **视觉细节（token / 间距 / 字阶 / 像素）以 `v4_android.html` 原型为准**，本节只给组件语义与配色/可达性约束。

### 8.1 组件清单（名称 · 用途 · 关键状态）

**导航与中枢**
- **BottomNav** — 三 Tab 切换；`default` / `active`（药丸底）。仅顶级中枢显示，进子页或采集运行时隐藏。
- **EntryTile** — 采集中枢「设备/用户/采集模式」入口；`default` / `pressed` / `disabled`（环境未就绪灰显引导权限）；变体 `primary`/`tertiary`/`success`。
- **GlobalCollectingBanner** — 采集运行中离开运行页时，采集 Tab 顶部常驻可回提示（脉冲点 + 计时）。

**采集配置**
- **SubjectCard** — 展示/选择用户；`selected` / `unselected` / `empty`；变体 `summary`/`full`/`editable`。建议用别名（隐私）。
- **ModeSegment（Wear/Unwear）** — 选中段实底白字，未选 `on-surface-v`；运行中只读。
- **GNSS 设置行** — `toggle` 变体，开启把本机 GPS 作为一路数据源；缺权限行内提示引导。

**数据与列表**
- **Search** — 过滤文件/用户/设备；`empty` / `typing` / `no-result`（联动空态）。
- **FileCard / SessionFolderCard**（同一组件）— 列一次会话（一会话=一文件夹）；`default` / `pressed` / `selected` / `selecting` / `degraded`（掉线段 warn 角标）。
- **EmptyState** — 列表/扫描/搜索无内容占位图标 + 说明 + 可选动作。
- **ManifestSummary** — 会话详情顶部元数据 Metric 网格 + 启用配置 pill；**不含任何 BLE 链路参数**。

**设置与系统**
- **SettingsRow** — 变体 `navigate`/`toggle`/`value`/`info`；含 `disabled`（二期项灰显标注）。
- **StorageBreakdown** — 多段 usage-bar + 图例 + 清理动作。
- **PermissionStatusRow** — ListTile + StatusPill（`ok`/`warn`）或行内动作；分硬性（缺失阻断）/ 建议（可继续）。

**反馈与运行态**
- **Toast** — `success`/`info`/`error`；`enter→停留~2s→exit`。不承载阻断信息。
- **ProgressDialog** — 导出等可观测耗时操作；`determinate`；失败转 Banner/Toast 不滞留。
- **Banner** — 采集运行内：暂停/重连（info 蓝）/ 已停止（err 红）；精简后不做红黄蓝多路分级。
- **RunLog** — 等宽时间戳行；坏包/CRC/解码失败/重连只记录不打断。
- **DeviceList** — 扁平行 + 状态徽章（未连/连接中/已连）；单击连、再点断、连接计数、DUT≤3。
- **LongPress** — 环形/线性 2 秒进度，停止采集二次确认。

**复用基础积木**（沿用现状）：Card（默认/扁平/tonal/ghost 虚线占位）、ListTile、StatusPill（ok/warn/err/idle + 脉冲点）、Pill/频率徽章、Switch、SensorTile（迷你波形+实时值）、WaveBig、Packet（批包紫/组帧绿+Hex）、Metric、Sheet、CmdLog（→CMD/←ACK）。

> **已移除**：BandwidthBar 与打包策略配置页 —— App 不配置 BLE 链路参数；带宽/包速率/丢包/CRC 降级为采集运行内只读观测指标（且经 D-V4-7 进一步移出运行界面），不再阻断开始。

### 8.2 传感器固定配色语义

同一传感器在任何界面用同一颜色；**色 + 文字双编码**，色彩不作唯一信息载体。

| 传感器 | 主色 | 语义/频段 |
| --- | --- | --- |
| PPG | 绿 `#2AAE6D` | 高频·批包 |
| ECG | 红 `#E5484D` | 高频·批包 |
| IMU | 蓝 `#2BAEEA` | 高频·批包 |
| 地磁 Magnetometer | 紫 `#7C5BC9` | 低频·组帧 |
| 温度 Temperature | 橙 `#E89F40` | 低频·组帧 |

存储占用条类别配色：原始 HEX 日志 = `warning` 橙、解码 CSV = `success` 绿、组合包 CSV = `primary` 蓝、GNSS CSV = `tertiary` 紫、manifest = `outline` 灰。

### 8.3 可达性

- **色彩不作唯一信息载体**：状态同时用图标 + 文字；传感器/权限状态均色 + 文字双编码。
- **对比度**：正文满足 WCAG AA；传感器浅底配深色文字（`on-*-c` token，如 `on-primary-c`/`on-success-c`）。
- **触控目标**：≥ 44×44pt（iOS）/ 48×48dp（Android），含 BottomNav 项与权限行内动作小按钮。
- **动态字号**：用相对字号支持系统 Dynamic Type / 字体大小调节。注意：V4「采集页一页放下不滚动」在大字号下需允许降级为可滚动，避免裁切。
- **关键操作二次确认**：停止采集用长按 2 秒 + 触感反馈。
- **语义角色与播报**：BottomNav `role=tab` + `aria-selected`；ModeSegment `role=radiogroup`；SettingsRow toggle 行 `role=switch`；Toast `role=status`（aria-live polite）；ProgressDialog `role=progressbar` + `aria-valuenow`；EntryTile/SubjectCard 以「键：值」播报。

---

## 9. 引用与归档

### 9.1 主线文件（开发与审阅）
- **本规格** `E:\BlueTrace\SPEC.md`
- **UI + 交互单一真源** `E:\BlueTrace\Docs\prototypes\v4_android.html`（含 37 屏 `.screen-ux` / `.screen-flow` / `.screen-label` 交互规格块）
- **机器可读协议契约** `E:\BlueTrace\Docs\architecture\bluetrace_v0.proto`（D-10 单一事实源）

### 9.2 全部归档（仅历史参考 · 本规格已内联其执行口径）

> 下列详细文档已全部移入 `Docs/legacy/`（主线只留本规格 + 原型 + `.proto`）。其执行要点均已内联本规格；**任何与本规格冲突处，一律以本规格（V4 + 2026-06-17 最新口径）为准**。归档文档内部交叉链接可能失效，仅作历史溯源。

- `Docs/legacy/REQUIREMENTS.md` —— 原需求条款 + §9 追踪矩阵（F-* 已精炼内联 §2）
- `Docs/legacy/BlueTrace_Protocol.md` —— 帧头/分片/CRC 链路事实（已内联 §4.3–4.9）
- `Docs/legacy/BlueTrace_Architecture.md` —— 文件/manifest/对齐/MediaStore/三 Tab 脚手架（已内联 §6/§7）
- `Docs/legacy/BlueTrace_UI_Implementation.md` —— 模块边界/SessionController/前台服务（已内联 §7）
- `Docs/legacy/BlueTrace_CrossPlatform_Notes.md` —— iOS 分叉点 X0–X4（已内联 §7.5）
- `Docs/legacy/BlueTrace_Design_System.md` —— 组件/配色/可达性（已内联 §8；视觉以原型为准）
- `Docs/legacy/BlueTrace_V4_设计契约_2026-06-16.md`、`Docs/legacy/BlueTrace_V4_决策追踪_2026-06-16.md` —— 决策记录（D-* / D-V4-* 已内联 §3）
- `Docs/legacy/BlueTrace_设计审查_2026-06-16.md` —— 早期设计审查（10 维）
- `Docs/legacy/BlueTrace_PRD.md`、`Docs/legacy/BlueTrace_UX_Flows.md`、`Docs/legacy/BlueTrace_UI_Design.md` 等 —— 更早归档

---

## 10. 开发就绪（KMP · Android-first）

> 本节是「**按本规格从零实现**」的工程总纲。一期 Android 优先、KMP 结构；iOS 后期从 commonMain 平移。实现以 **本规格 + 原型 `v4_android.html` + `bluetrace_v0.proto`** 为唯一依据（不依赖任何既有脚手架）。

### 10.1 工程结构（KMP 模块与包）

- **`:shared`（commonMain · 纯 Kotlin · 无 Android 依赖 · 可 JVM 单测）**
  - `protocol/`：标准帧头解析 / 分片重组 / 一包多帧拆分 / `msgType` dispatch；Wire 生成的 protobuf 消息（控制/低频/事件/能力）；**高频批包 `bytes` 手写定宽字节解析**（§4）。
  - `session/`：采集会话状态机（采集中 / 断联自动重连 / 结束，§5.4）、`SessionController` 接口与采集编排、运行错误/事件日志通道。
  - `data/`：raw HEX 与解码 CSV 的 **append 写入器（okio）**、D-6 会话文件夹布局（§6.1）、manifest 模型（kotlinx.serialization，§6.2）、unix 时间戳工具（§6.3）。
  - `domain/`：实体（**扁平设备模型**、`Subject`、`Mode`、`Session`…）+ repository 接口（设备扫描 / 环境与权限 / 会话 / 用户）。
  - `ble/`：`BleClient` 接口（扫描 / 连接 / 订阅 Notify / 写 / 状态流），平台各自 actual 实现。
- **`:app`（Android）**
  - Compose UI 按原型逐屏渲染；**三 Tab + `NavigationSuiteScaffold` + 类型安全路由**（§7.2）。
  - `BleClient` 的 **Nordic Kotlin-BLE** 实现；前台服务托管采集（§7.4）；MediaStore 导出（§6.4）；权限/蓝牙门控（§5.2）；DataStore（prefs）；Koin（DI）。
- **`:ios`（iosMain，后期）**：`BleClient` 的 Core Bluetooth 实现 + SwiftUI（§7.5）。

### 10.2 技术栈（D-V4-19）

| 层 | 选型 |
| --- | --- |
| 工程结构 | **KMP**：`:shared`（commonMain 纯 Kotlin：协议解析 / 会话状态机 / CSV 行格式 / manifest 模型 / repository 接口；可 JVM 单测）+ `:app`（Android：Compose UI / BLE / 前台服务 / MediaStore / 权限）；iOS 后期加 `iosMain` |
| BLE | androidMain **Nordic Kotlin-BLE**（Flow 化、重连/MTU 稳健），藏在 commonMain `BleClient` 接口后；iOS 后期 Core Bluetooth 实现同接口 |
| protobuf | **Wire**（`.proto`→Kotlin，commonMain，D-10 单一事实源）；**高频批包 `bytes` 走手写字节解析**（§4.7 定宽布局），protobuf 仅控制/低频/事件/能力 |
| 文件 IO | **okio**（commonMain 共享 raw HEX / CSV append 写入）；MediaStore 导出在 androidMain |
| 序列化 | **kotlinx.serialization**（manifest.json） |
| DI | **Koin**（KMP） |
| 导航 | **Navigation Compose 类型安全**（`@Serializable` 路由）+ `NavigationSuiteScaffold`（见 §7.2） |
| prefs | **DataStore**（首启标记等） |
| 并发 | kotlinx.coroutines（数据管线 `Flow<SensorSample>`） |
| SDK | minSdk **29** / target·compile **36**（跟进发布）/ Kotlin 2.2.x / AGP 9.x / Compose BOM |

### 10.3 核心模型实现要点（钉住 V4 口径）

- **设备 = 扁平列表 + 限额**（DUT ≤3 + 参考 ≤1，D-V4-3）：**不是**"每角色一台"的 `Map<Role,Device>`；Device 按 `kind` 区分（自研多传感器 DUT / 标准 HRS `0x180D` 心率带）。至少一台连接才放行开始（§5.3）。
- **会话级实体**：`Subject`（用户）/ `Mode`（Wear/Unwear）写入文件名前缀 + manifest（§6.2）。
- **状态机**：三态（采集中 / 断联重连内联 / 存储满自动结束）+ 全局诊断日志（§5.4）；采集运行**硬锁定**、在场感=前台服务通知、进程恢复=服务重绑 / 开口会话自动收尾（§5.10）。
- **时间全用 unix 时间戳**（§6.3）；manifest 记时区 + 起点 + 质量小结。
- 协议未冻结时用 **`SessionController` 的 Fake 实现 + 录制回放** 跑通 UI / 导航 / 状态机（Fake 是接口替身，非临时 mock）。

### 10.4 协议冻结清单（并行轨 · 与固件共同冻结，gates 解码层 P2/P5）

ver 值 · 字节序（小端待确认）· `DUT_NOTIFY/WRITE` 服务/特征 UUID · `SensorId` 枚举 · `msgType` 注册表定版 · 各传感器 `SampleFormat`/通道/采样率 · `hdrCrc8`/payload CRC 开关。冻结前用 **Fake + 录制回放**推进 UI 与状态机（§10.3）。

### 10.5 开发顺序（沿用 §2.8）

- **P1**：KMP 骨架 + 权限/扫描 + 三 Tab 导航 + `BleClient` 接口与 Nordic 实现 + Fake 跑通。
- **P2**：扁平设备（DUT≤3 + 参考≤1）/ 纯开关采集类型 / 三态状态机。
- **P3**：D-6 持久化 / 导出（MediaStore）/ GNSS / manifest。
- **P4**：后台前台服务 / 进程恢复（硬锁定 + 服务重绑 + 开口会话自动收尾）。
- **P5**：固件联调（协议冻结后接真机解码）。

> 机器契约见 `Docs/architecture/bluetrace_v0.proto`。
