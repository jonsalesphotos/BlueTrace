# BlueTrace 需求总纲（控制文档）

> **这份文档是什么**：BlueTrace 的**单一事实源 + 计划控制台**。它把 `Docs/` 下分散的产品、交互、设计、架构材料**收敛成一份可追踪的需求与计划清单**，用来对齐范围、驱动讨论、控制进度。
>
> **它和 `Docs/` 的关系**：`Docs/` 是分层详版（怎么想、长什么样、怎么落地）；本文是其**上层索引与控制面**——只放"做什么、做到什么程度、做到哪一步了"，细节链接回各详版，不重复正文以免漂移。
>
> **怎么用**：
> 1. 范围有疑问 → 看 §3 目标 / §5 需求清单；
> 2. 要排期 / 看进度 → 看 §8 里程碑 + §9 追踪矩阵（**改进度只改这两处**）；
> 3. 要拍板技术方案 → 看 §11 待决策清单，逐条在讨论中敲定后回填本文。

| 项 | 内容 |
| --- | --- |
| 文档版本 | v0.10（V4 收敛，对应原型 v4_android） |
| 项目状态 | **设计阶段**——仅文档与原型，暂无源码 |
| 目标平台 | **Android**（minSdk 29 / target 35）+ **iOS**（iOS 15+） |
| 设计策略 | 统一设计语言 + 平台自适应 |
| 信息架构 | **V4 底部三 Tab（采集 / 数据 / 设置）为唯一主线**；v3 向导式仅 legacy 参考 |
| 详版入口 | [README](README.md) · [文档导航](Docs/README.md) · [PRD](Docs/product/BlueTrace_PRD.md) · [交互](Docs/product/BlueTrace_UX_Flows.md) · [设计系统](Docs/product/BlueTrace_Design_System.md) · [V4 设计契约](Docs/reviews/BlueTrace_V4_设计契约_2026-06-16.md) · [架构](Docs/architecture/BlueTrace_Architecture.md) · [跨平台](Docs/architecture/BlueTrace_CrossPlatform_Notes.md) |

---

## 1. 一句话定义

BlueTrace 是一个 **BLE 多传感器数据采集 App**：在一次会话中，稳定采集待测设备（DUT）的多路异构信号（PPG/ECG/IMU/地磁/温度，可扩展），支持 **扁平多设备连接**（采集单设备优先；DUT ≤3 + 心率带参考 ≤1），手机端**开关传感器路**（透明传输，不控采样率 / 不配链路参数；设备端算法为后期），全程不丢数据、可后台、可恢复、可导出。首个落地场景是 **PPG 信号评测**。

## 2. 背景与问题（为什么做）

- 没有现成工具能在采 PPG 的同时，把 **ECG / IMU / 地磁 / 温度** 多路信号同步落盘并可后期对齐。
- 评测常需 **DUT 与标准参考设备（心率带）对照采集**，现有 App 不支持多设备并行 + 角色化管理。
- 研发调试需要能**选择采哪些传感器路**（开关 / 透明传输，不改采样率）；设备端算法为后期，而非被动接收。
- BLE 带宽有限，多路高频信号必须有明确的**打包/分包策略**（高频批包、低频组帧），否则丢包严重。

## 3. 目标与非目标（做什么 / 不做什么）

### 3.1 一期目标
1. 一次会话稳定采集 DUT **多路传感器**并各自落盘。
2. 支持 **1..N 设备并行**，代码与体验对设备数量透明（单设备 = 多设备的退化，不分两套路径）。
3. 手机端**选择采集哪些传感器路**（开关，透明传输，不控采样率）；设备端算法为后期。
4. 显式呈现 **BLE 打包策略**（批包/组帧）并给带宽预估。
5. 采集**全程不丢数据**：后台持续采、异常分级、进程被杀可恢复。
6. 数据可被用户**找到并导出**到公共目录。
7. **Android 与 iOS** 体验一致。

### 3.2 非目标（本期不做，归二期或不做）
- 服务器上传 / 远程下发指令 / 实时透传（→ 二期）；**"在线/离线"双采集模式随服务器接入归二期，一期只有单一本地采集（V4 已移除"离线收集"CTA）**。
- 离线分析、信号质量评分、报表（在 App 外做）。
- 多用户账号、云同步、复杂权限管理。
- BLE 广播（本 App 仅作 Central，永不做 advertiser）。
- 完全无人值守的开机自动重连（受平台后台限制，靠用户回 App 恢复）。

## 4. 目标用户

| 画像 | 关心什么 | 典型动作 |
| --- | --- | --- |
| **算法/评测工程师**（主） | 多路同步、对照参考、可对齐、可导出 | DUT + 心率带对照，导出 CSV 分析 |
| **固件/硬件工程师** | 选择采哪些路、看实时数据 / 原始日志、核对帧格式 | 开关传感器路、看实时数据流 / 运行日志 |
| **测试** | 异常清晰、长时不丢、可恢复 | 长测、制造断连验证恢复 |

## 5. 需求清单（带编号与优先级）

> 优先级：**P0** = 一期必做（缺了不可用）；**P1** = 一期应做（体验关键）；**P2** = 可延后。
> 编号沿用 PRD，作为 §9 追踪矩阵与后续提交/分支的引用锚点。详细交互见 [UX Flows](Docs/product/BlueTrace_UX_Flows.md)。

### 5.1 权限与环境
| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-PERM-1 | 检查蓝牙开关、BLE 扫描/连接权限、定位（按平台），区分硬性/建议条件 | P0 |
| F-PERM-2 | 缺硬性条件阻断采集并给修复入口；建议条件（通知）可继续 | P0 |
| F-PERM-3 | 系统权限弹窗前先展示用途说明层 | P1 |

### 5.2 设备接入
| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-DEV-1 | 按 Service UUID 过滤扫描 DUT / REFERENCE，显示名称、RSSI、MAC | P0 |
| F-DEV-2 | **扁平设备列表**：单击连接/断开。**DUT ≤3 台**（第 3 起不稳定、第 4 禁止）+ **至多 1 个标准心率带参考**（HRS 0x180D 对照）；过滤自己的 DUT + 标准 HRS。无角色槽，采集单设备优先 | P0 |
| F-DEV-3 | 至少一个有效设备连接成功才允许开始采集 | P0 |
| F-DEV-4 | 记住上次设备，恢复时优先用已知地址定向重扫 | P1 |

### 5.3 设备配置与控制 ★核心
| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-CTRL-1 | 列出 DUT 传感器，逐路**纯开关**（开关＝该路是否采集/上传，透明传输）；不外露高/低频 | P0 |
| F-CTRL-2 | ~~每路可选采样率~~ → **后期**：当前透明传输、App 不控采样率（开关只决定是否采集该路） | 后期 |
| F-CTRL-3 | 选择 DUT 设备端算法（HR/SpO₂/RR/计步/跌倒等），结果随流回传 | P1 |
| F-CTRL-4 | ~~运行内只读观测包速率/丢包/带宽~~ → **后期/调试**：采集页不做观测面板；坏包/CRC/丢包进运行日志。`CMD_SET_PACKING` 仅固件调试 | 后期 |
| F-CTRL-5 | ~~运行中控制面板 + 指令日志/ACK~~ → **后期**：当前透明传输，运行页不下发设备控制指令 | 后期 |

### 5.4 采集与可视化
| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-COL-1 | 数据采集页（照竞品 Data Collection）：设备卡(含 HR) + 采集类型勾选 + Datas/Time **简单实时数据区** + Start/End 标签 | P0 |
| F-COL-2 | ~~双视图：波形 ⇄ 原始分包流~~ → **后期**（V4 精简移除） | 后期 |
| F-COL-3 | ~~原始分包流 + 包速率/丢包/CRC 观测~~ → **后期**；原始 HEX 仍按 D-6 落盘为 source of truth | 后期 |
| F-COL-4 | 显示已采时长、已存行数、当前文件大小 | P0 |
| F-COL-5 | 采集中可打时间标签 | P2 |

### 5.5 会话与文件
| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-FILE-1 | 每会话一个文件夹（同一时间仅一个任务）：解码后每路独立 CSV + 会话级 manifest（起点时间、对齐锚点、设备控制配置） | P0 |
| F-FILE-2 | 采集结束展示摘要（时长、设备、各路文件与行数、异常） | P0 |
| F-FILE-3 | 历史会话列表与详情，展示传感器组合 | P1 |
| F-FILE-4 | 导出到公共目录（Android Downloads / iOS Files & 共享） | P0 |
| F-FILE-5 | 删除会话（前置确认） | P1 |
| F-FILE-6 | 原始抓包日志（主体·无损）：每来源一份 `<epochMs>: HEX` 行日志，append-only、行级崩溃安全、可重放；后期加流式压缩（zstd） | P0 |
| F-FILE-7 | 组合包兼容 CSV：对汇顶等 PPG+ACC 组合包生成专用合并 CSV；输出映射按消息类型 1→N 文件 | P1 |

### 5.6 后台、异常与恢复
| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-BG-1 | 后台/锁屏持续采集（平台各自机制） | P0 |
| F-BG-2 | 退出采集需明确确认（防误触，如长按 2 秒） | P0 |
| F-BG-3 | 异常精简为三态：采集中 / 暂停·重连（连接断→自动重连续写） / 已停止（不可继续才停）+ 运行日志；无法处理的错误记日志并丢弃，不分级、不打断 | P0 |
| F-BG-4 | 进程被杀后回 App 可逐角色恢复或引导重扫 | P1 |
| F-BG-5 | 采集以**前台服务**模型常驻（如音乐播放器）：常驻通知；被划掉/被系统回收靠 `START_STICKY` + 自启动/电池白名单引导（尤其国产 ROM）自恢复；仅用户进 App 长按结束才停止 | P1 |

### 5.7 数据源扩展（本机 GPS）

> BlueTrace 的数据源不止 BLE 设备——把**手机自身的 GPS**作为一路数据并入会话，用于户外运动算法。**V4 契约将其升为一期正式功能**（设置页 GNSS 开关），数据模型与权限 posture 见 §10、D-3。

| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-GPS-1 | 本机 GPS 作为一路数据源记录（经纬度/海拔/速度/精度/时间戳），与 BLE 多路共用同一会话、同一 monotonic 对齐锚点、独立 CSV；用于户外运动算法 | P1（一期实现） |

### 5.8 用户与采集模式（V4 · 一期）

> V4 把"采集用户/用户"与"佩戴/未佩戴模式"纳入一期：二者都写入文件名与 manifest，是 PPG 评测分类与离线分析的关键元数据。详见 [V4 设计契约 §六](Docs/reviews/BlueTrace_V4_设计契约_2026-06-16.md)。

| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| F-SUBJ-1 | 用户实体（别名/性别/生日/身高/体重），本地存储、可多条、可选当前；采集前在采集 Tab 选择 | P0 |
| F-SUBJ-2 | 用户写入文件名前缀与会话 manifest；建议用别名而非真实姓名、一期不上传（隐私） | P0 |
| F-MODE-1 | 采集模式 Wear/Unwear（会话级），运行头只读显示，写入文件名前缀与 manifest，数据 Tab 可按模式筛选 | P0 |

## 6. 关键规则（不可违背的产品约束）

来自 [UX Flows §7](Docs/product/BlueTrace_UX_Flows.md)，作为开发统一依据：

1. 用户必须随时知道是否正在采集（运行中全局提示条）。
2. 采集中返回键/手势 ≠ 停止；停止必须明确确认（长按 2 秒）。
3. 至少一个设备连接成功才允许开始采集。
4. 单路/单设备异常不得中断其它路/设备的落盘（`supervisorScope`，按角色隔离）。
5. 异常精简为三态（采集中 / 暂停·重连 / 已停止）+ 运行日志；错误记日志不打断，不做红黄蓝分级。
6. 设备控制指令即时生效并写入会话 manifest。
7. 采集结束必须有结果摘要；文件必须能被找到和导出。
8. 平台外壳自适应，产品语言统一。

## 7. 平台支持矩阵

| 能力 | Android | iOS | 说明 |
| --- | --- | --- | --- |
| 扫描/连接/多设备采集 | ✅ | ✅ | 核心一致 |
| 设备控制（开关/采样率/算法） | ✅ | ✅ | 协议一致 |
| 后台持续采集 | 前台服务 + WakeLock | Core Bluetooth 后台模式 + 状态保存/恢复 | 机制不同，目标一致 |
| 进程被杀恢复 | 用户回 App / 启动恢复 | CBCentralManager Restoration + 用户回 App | iOS 无前台服务概念 |
| 导出 | MediaStore / 公共 Downloads | Files App / 分享 Sheet | 目录模型不同 |

**交互原则**：流程、状态、信息层级、配色、数据可视化**两端完全一致**；只有系统级外壳（导航条、弹层形态、安全区、权限/后台机制）按平台走。

## 8. 实施里程碑（计划控制核心）

> 进度只在这里和 §9 维护。状态图例：⬜ 未开始 · 🟡 进行中 · ✅ 完成 · 🔵 二期/暂缓。
> Android 详版见 [架构 §17](Docs/architecture/BlueTrace_Architecture.md)，iOS 叠加见 [跨平台 §7](Docs/architecture/BlueTrace_CrossPlatform_Notes.md)。

### 8.1 一期 · 本地采集闭环（Android 主线）
| Phase | 目标 | 验收 | 覆盖需求 | 状态 |
| --- | --- | --- | --- | --- |
| **P0 探针** | 用 `:sample` 真机跑通 Kotlin-BLE v2.0，确认 API 在 Android 7/14/15 行为 | 真机日志看到 notify | — | ⬜ |
| **P1 骨架** | 新建 `:bluetrace-app`，接 Hilt(Singleton)/Timber/Environment/CentralManager，完成权限页 + 扫描页(mock) | mock 设备能在扫描页列出 | F-PERM-1/2/3, F-DEV-1 | ⬜ |
| **P2 Profile+Pipeline** | `SensorProfile` + DUT/HeartRate 实现 + `MultiDeviceController`(含重连) + `CollectionPipeline` + **TLV+protobuf 解码器**（依赖协议 schema 冻结，见 D-4/D-8）；显示 saved/invalid | mock 单设备 + 双设备 happy path 均通过 | F-DEV-2/3, F-CTRL-1/2, F-COL-1/4, F-BG-3 | ⬜ |
| **P3 持久化+文件** | `CsvSensorDataSink`(Channel+滚动)；导出 Downloads(MediaStore / API24-28 公共目录) | 真机生成可读 CSV(单/双)，能导出 | F-FILE-1/2/4 | ⬜ |
| **P4 后台+恢复** | 前台服务(connectedDevice+WakeLock)；`SessionStore`+`Resumer`(稳定 MAC 恢复 + 地址失效重扫)；开机引导通知 | ①灭屏持续采集 ②进程被杀回 App 续写同一 CSV | F-BG-1/2/4, F-DEV-4 | ⬜ |
| **P5 固件联调** | 对接真实设备、冻结/校准自研协议（schema 版本化）；上层零改动，diff 限定在协议 schema 与 `profile/dut/` | 双端按同一 schema 跑通真机采集 | F-CTRL-3 | ⬜ |

> 余下 P1 级增强（F-CTRL-4/5 打包策略与运行中控制、F-COL-2/3 双视图与分包流、F-FILE-3/5 历史与删除、F-COL-5 时间标签）穿插在 P2–P4 落地，见 §9。
>
> **GPS 预留（D-3 / F-GPS-1）**：P2/P3 的数据模型按 `DataSource` 抽象设计——BLE 设备与本机 GPS 同为"源"，共用 pipeline/sink/manifest；一期只预留不实现。GPS 采集落地为 P5 之后的增量阶段 **P-GPS**（前台服务加 `location` 类型 + 申请定位/通知权限）。
>
> **协议设计前置（D-4）**：自研 TLV+protobuf 协议规范（信封格式 + 类型注册表 + `.proto` schema）是 P2 解码器的上游交付物，需与固件端共同冻结。✅ **v0.1 草案已产出并过一轮多视角评审**：[协议规范](Docs/architecture/BlueTrace_Protocol.md)（修订记录见 §16）+ [bluetrace_v0.proto](Docs/architecture/bluetrace_v0.proto)，待固件按规范 §15 逐项冻结为 v1。

### 8.2 跨平台 · iOS 叠加（在一期 Android 基础上）
| Phase | 目标 | 状态 |
| --- | --- | --- |
| **X0** | 抽出 commonMain 领域层（协议/解析/打包/会话/CSV），JVM 单测覆盖 | ⬜ |
| **X1** | Android 端接 actual（沿用一期实现） | ⬜ |
| **X2** | iOS 接 Core Bluetooth actual：扫描/连接/订阅/写指令打通 | ⬜ |
| **X3** | iOS 后台模式 + State Restoration；进程恢复对齐 | ⬜ |
| **X4** | iOS 文件导出（Files App 共享）；双端走查一致性 | ⬜ |

> ✅ **路线已定（D-1/D-2，2026-06-15）**：走 **Android 原生先行**——先做纯 Android `:bluetrace-app` 跑通一期 P0–P5；iOS **紧随其后**、不并入一期排期。一期的纯逻辑层（协议/解析/打包/会话状态机/CSV/manifest）从第一天就按 **KMP-ready** 写（不引 Android 依赖、可 JVM 单测），等 iOS 启动时在 **X0** 平移进 commonMain，X1 起按 §8.2 推进。

### 8.3 二期 · 服务器远控 / 上传（本期不做）
| Phase | 目标 | 状态 |
| --- | --- | --- |
| **P6** | 服务器接入（API 客户端 + 鉴权 + 重试） | 🔵 |
| **P7** | 周期上传（`CsvUploadWorker` + 文件清理 + 退避） | 🔵 |
| **P8** | 远程指令（服务器下发 → `sendCommand` 转发） | 🔵 |
| **P9** | 看门狗 + 远程诊断 | 🔵 |

## 9. 需求 ↔ 里程碑 ↔ 状态 追踪矩阵

> 每个需求一行；改进度改"状态"列。这是验收与进度对账的总账。

| 需求 | 优先级 | 归属 Phase | 状态 | 备注 |
| --- | --- | --- | --- | --- |
| F-PERM-1 | P0 | P1 | ⬜ | 真理源 = `AndroidEnvironment` |
| F-PERM-2 | P0 | P1 | ⬜ | |
| F-PERM-3 | P1 | P1 | ⬜ | iOS 上为上架硬要求（Info.plist 用途文案） |
| F-DEV-1 | P0 | P1 | ⬜ | Service UUID 过滤 |
| F-DEV-2 | P0 | P2 | ⬜ | 扁平列表 · 单击连接/断开 · DUT ≤3（第4禁止）+ 参考心率带 ≤1 · 无角色槽 |
| F-DEV-3 | P0 | P2 | ⬜ | |
| F-DEV-4 | P1 | P4 | ⬜ | 未绑定设备：MAC 不可靠，需 Service UUID 兜底 |
| F-CTRL-1 | P0 | P2 | ⬜ | 纯开关＝该路是否采集（透明传输）·不外露高/低频 |
| F-CTRL-2 | 后期 | — | 🔵 | V4 精简：透明传输，App 不控采样率 |
| F-CTRL-3 | P1 | P5 | ⬜ | 依赖真机协议 |
| F-CTRL-4 | 后期 | — | 🔵 | V4 精简：采集页不做观测面板；坏包/CRC/丢包进运行日志；CMD_SET_PACKING 仅调试 |
| F-CTRL-5 | 后期 | — | 🔵 | V4 精简：透明传输，运行页不下发控制指令 |
| F-COL-1 | P0 | P2 | ⬜ | Creek 式：设备卡+采集类型勾选+Datas/Time 简单实时数据区+Start/End 标签 |
| F-COL-2 | 后期 | — | 🔵 | V4 精简移除波形⇄分包流双视图 |
| F-COL-3 | 后期 | — | 🔵 | V4 精简移除分包流观测；原始 HEX 仍按 D-6 落盘 |
| F-COL-4 | P0 | P2 | ⬜ | |
| F-COL-5 | P2 | P2+ | ⬜ | 可延后 |
| F-FILE-1 | P0 | P3 | ⬜ | 每路独立文件 + 会话 manifest（monotonic 锚点） |
| F-FILE-2 | P0 | P3 | ⬜ | |
| F-FILE-3 | P1 | P3 | ⬜ | |
| F-FILE-4 | P0 | P3 | ⬜ | Android MediaStore / iOS Files |
| F-FILE-5 | P1 | P3 | ⬜ | 前置确认 |
| F-FILE-6 | P0 | P3 | ⬜ | 原始 HEX 行日志（主体·可重放）；流式压缩后期 |
| F-FILE-7 | P1 | P3 | ⬜ | 组合包（汇顶 PPG+ACC）兼容合并 CSV；输出映射 1→N |
| F-BG-1 | P0 | P4 | ⬜ | Android FGS+WakeLock / iOS 后台模式 |
| F-BG-2 | P0 | P2 | ⬜ | 长按 2 秒确认 |
| F-BG-3 | P0 | P2 | ⬜ | 三态（采集中/暂停重连/已停止）+ 运行日志；不分级、不打断 |
| F-BG-4 | P1 | P4 | ⬜ | 逐角色恢复 / 重扫 |
| F-BG-5 | P1 | P4 | ⬜ | 前台服务 + START_STICKY + 自启动/白名单引导；不依赖背景定位权限 |
| F-GPS-1 | P1 | P3（一期实现） | ⬜ | DataSource 抽象；前台服务 location 类型；while-in-use 定位足够 |
| F-SUBJ-1 | P0 | P2/P3 | ⬜ | Subject 实体 + 选择；写入命名/manifest |
| F-SUBJ-2 | P0 | P3 | ⬜ | 别名优先；一期不上传（隐私） |
| F-MODE-1 | P0 | P2/P3 | ⬜ | Wear/Unwear 会话级；命名/manifest/筛选 |

## 10. 技术约束与关键决策（已定）

> 详见 [架构 §18](Docs/architecture/BlueTrace_Architecture.md) 与 [跨平台 §1](Docs/architecture/BlueTrace_CrossPlatform_Notes.md)。这里只列影响计划的硬约束。

- **信息架构 = V4 底部三 Tab**（采集 / 数据 / 设置）为唯一主线；v3 向导式 IA 降为 legacy 参考——其成熟的异常态 / 采集状态机 / 恢复流程纳入 V4 外壳复用。采集运行与子页隐藏底部 Tab（强状态任务防误切）。
- **App 不配置 BLE 链路参数**（批包/组帧/MTU/连接间隔由设备按功耗驱动，D-5）：删除打包策略配置页；带宽/包速率/丢包/CRC 降为采集运行内**只读观测**，不阻断开始（D-9）。
- **BLE 仅作 Central**，底层用 **Kotlin-BLE-Library v2.0**（CentralManager/Peripheral/Flow）；不引入 advertiser，不声明 `BLUETOOTH_ADVERTISE`。
- **设备数量 1..N 透明**：`DeviceAssignment = Map<DeviceRole, Peripheral>`（size≥1），单设备是退化情形，不存在两套代码路径。
- **单一真理源**：应用级 `SessionState`/`DeviceState` 全部由 `peripheral.state` + `env.bluetoothState`/`locationState` 派生，不自造 BLE 状态。
- **Profile 隔离协议**：换 DUT 协议只动 `*ProfileImpl` + `*DataParser`，上层零改动。
- **作用域**：Environment / CentralManager / AppScope 全 `@Singleton`，与 Application 进程同生命周期（FGS 长驻所需）。
- **DUT 未绑定（no bonding）、明文 GATT、地址可能轮换**：恢复必须靠 Service UUID 兜底，不能只信 MAC 缓存。
- **REFERENCE 走标准 SIG Heart Rate Service（0x180D/0x2A37）**，市售心率带开箱即用。
- **后台保活机制不同、目标一致**：Android 前台服务必须由前台用户动作启动；iOS 靠 Background Modes `bluetooth-central` + State Restoration。
- **WorkManager 仅二期用**（周期上传/清理/看门狗），本期不引入。
- **跨平台分层**：纯字节与状态逻辑入 commonMain 共享；碰系统 API（BLE/权限/后台/文件）走平台 actual。
- **工程形态 = Android 原生先行**（D-1/D-2 已定）：一期落 `:bluetrace-app`，iOS 紧随。**硬约束**：一期纯逻辑层（协议/解析/打包/会话状态机/CSV/manifest）必须 **KMP-ready**——不引入任何 Android 类型、纯 Kotlin、可 JVM 单测，以便 X0 机械平移进 commonMain。
- **数据源抽象 = DataSource**（D-3 / F-GPS-1）：BLE 设备与本机 GPS 统一为"数据源"，共用 pipeline / sink / manifest 与对齐锚点，保持无两套代码路径；GPS 一期预留、后期实现。
- **定位权限 posture（D-3 已定）**：保留 `ACCESS_FINE_LOCATION`（while-in-use），**不声明 `neverForLocation`**（GPS 是真实数据源）；GPS 续采靠**前台服务 `location` 类型**（+ `FOREGROUND_SERVICE_LOCATION` + `POST_NOTIFICATIONS`），**不申请重监管的 `ACCESS_BACKGROUND_LOCATION`**——采集恒由前台用户动作启动并以前台服务长驻，while-in-use 足够。iOS 对应：`NSLocationWhenInUseUsageDescription` + `UIBackgroundModes: location`。
- **通信协议 = 自研 TLV + protobuf**（D-4 方向已定）：**必须有固定标准头部**（版本 / 消息类型 T / flags / msgId / seq / 分片索引·总数 / 长度）——protobuf 本身不带框架头，TLV 头负责类型分发、批包-组帧分界、以及**分包/组包**（单条消息超 MTU 时跨多包分片，按 msgId+fragIdx 重组）；内层 protobuf 做 payload，跨片组包还原后才解码。双向（设备→app 数据流 + app→设备 控制/ACK），seq + 设备时间戳配合 app 侧 monotonic 锚点做丢包检测与对齐；解码层落 commonMain（KMP-ready）。BLE **全部链路参数（连接间隔 + MTU 等）由设备按功耗申请，app 完全不参与、只解包**。高频/低频编码见 D-8。
- **存储模型（D-6 已定）**：每会话一个文件夹 = ① **原始 HEX 行日志**（主体 / source of truth，按来源一份，`<epochMs>: HEX`，无损可重放，后期流式压缩 zstd）② 解码后按模块 CSV（显示用内存 + 落盘分析）③ 组合包兼容 CSV（汇顶 PPG+ACC 等）④ `manifest.json`。Sink 从"1 角色 1 文件"放宽为"**1 消息类型 → 1..N 文件**"。原始日志保证即便解码逻辑 / schema 变更也能回溯重放。
- **SDK / 兼容（D-7 已定）**：Android **minSdk 29**（Android 10）/ target 最新稳定（35，按发布跟进 36）；iOS 15+。minSdk 29 简化红利：导出走**纯 MediaStore**（去 `WRITE_EXTERNAL_STORAGE` maxSdk≤28 路径）、**`java.time` 原生可用**（去 core-library desugaring）。仍保留分支：扫描权限 29–30 ↔ 31+、`POST_NOTIFICATIONS`(33+)、FGS 类型权限(34+)。**下游待同步**：架构 §3.1（去 desugaring）、§5（去 `WRITE_EXTERNAL_STORAGE`）。

## 11. 待决策清单（"探讨具体实现"的议题）

> 这些是动工前需要拍板的开口项；敲定后回填到对应章节并更新状态。

| 编号 | 议题 | 选项 / 现状 | 影响 |
| --- | --- | --- | --- |
| ~~D-1~~ | ~~工程形态：先纯 Android 原生，还是一上来就 KMP？~~ | ✅ **已定（2026-06-15）：Android 原生先行**，纯逻辑层按 KMP-ready 写 | 见 §10 工程形态约束、§8.2 |
| ~~D-2~~ | ~~iOS 是否纳入一期~~ | ✅ **已定（2026-06-15）：iOS 不并入一期，紧随其后**（X0 起） | 见 §8.2 |
| ~~D-3~~ | ~~`neverForLocation` 取舍~~ | ✅ **已定（2026-06-15）：保留定位、不声明 neverForLocation**——后期需记录本机 GPS 做户外运动算法（F-GPS-1），定位是真实数据源 | 见 §5.7、§10 定位 posture |
| **D-4** | DUT 通信协议 | ✅ **方向已定（2026-06-15）：自研专用协议 = 外层 TLV 信封 + 内层 protobuf，双向（数据流+控制/ACK）、含 sequence/时间戳、类型注册表可扩展**；不再外部阻塞，改为我方设计交付物。schema 细节见 D-8/D-10 | 解析层、P2、P5、F-CTRL-*、F-COL-3 |
| ~~D-5~~ | ~~打包策略默认参数~~ | ✅ **已定（2026-06-15）：BLE 链路参数由设备按功耗驱动、app 只解包**；应用层打包并入协议设计（D-4）；带宽/丢包定位见 D-9 | 见 §10 协议、D-4/D-9 |
| ~~D-8~~ | ~~高频样本编码 + 分包/组包~~ | ✅ **已定（2026-06-15）：混合编码**（高频批包紧凑 `bytes`/`packed`、低频/控制结构化 protobuf）+ **标准头部支持分片/重组**（大消息超 MTU 跨包，按 msgId+fragIdx 组包） | BLE 带宽、解析层 |
| ~~D-9~~ | ~~F-CTRL-4 重定位~~ | ✅ **已定（2026-06-15）：控制面留可选 `CMD_SET_PACKING`（固件调试）+ 观测面实时显示包速率/丢包/带宽**；去掉"带宽超阈值阻止开始"的硬闸 | F-CTRL-4、F-COL-3 |
| ~~D-10~~ | ~~protobuf 实现与契约归属~~ | ✅ **已定（2026-06-15）：Wire + `.proto` 单一事实源**；**固件自研（同团队）**，`.proto` 内部共管、双端同一 schema，无外部依赖 | commonMain 解码层、固件 |
| ~~D-6~~ | ~~文件格式~~ | ✅ **已定（2026-06-15）：原始=HEX 行日志（主体，后期流式压缩）+ 解码=按模块 CSV + 组合包兼容 CSV**；每会话一文件夹；Sink 1 消息类型→1..N 文件 | 见 §10 存储、F-FILE-1/6/7 |
| ~~D-7~~ | ~~minSdk/targetSdk 与 iOS 最低版本~~ | ✅ **已定（2026-06-15）：Android minSdk 29（Android 10）/ target 35；iOS 15+** | 见 §10 SDK |

## 12. 成功指标

| 指标 | 目标 | 怎么量 |
| --- | --- | --- |
| 采集稳定性 | 连续采集 ≥ 2 小时无中断、丢包率 < 0.5% | 长测会话统计 |
| 数据完整性 | 进程被杀后可恢复且不损坏已落盘数据 | 强杀恢复测试 |
| 多设备并行 | DUT + REFERENCE 双连接稳定运行 | 双设备 happy path |
| 跨平台一致性 | 同一流程在 Android/iOS 均可完成且体验对齐 | 双端走查 |

## 13. 术语表

| 术语 | 含义 |
| --- | --- |
| **DUT** | Device Under Test，待测设备，本期为多传感器自研设备 |
| **REFERENCE** | 参考设备（标准心率带等），用于对照评测 |
| **会话 Session** | 一次"开始采集~停止"，含一组设备分配与对齐锚点 |
| **角色 DeviceRole** | DUT / REFERENCE / 未来扩展，决定用哪个 Profile |
| **高频批包** | 单一高频传感器缓存 N 个采样点后批量打成一个 BLE 包 |
| **低频组帧** | 多种低频传感器按周期打成一帧同发 |
| **manifest** | 会话元数据 JSON：起点时间、monotonic 对齐锚点、设备配置、文件清单 |
| **monotonic 锚点** | 单调时钟基准（`System.nanoTime()` 等），用于多路/多设备后期对齐 |

## 14. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v0.1 | 2026-06-15 | 首次从 `Docs/` 收敛出需求总纲 + 里程碑 + 追踪矩阵 + 待决策清单 |
| v0.2 | 2026-06-15 | 拍板 D-1/D-2：Android 原生先行、iOS 紧随；新增 KMP-ready 硬约束（§10），解除 §8.2 路线冲突 |
| v0.3 | 2026-06-15 | 定 D-3：保留定位、不声明 neverForLocation；新增 F-GPS-1（本机 GPS 数据源·一期预留/后期实现）、F-BG-5（前台服务自恢复）；明确 DataSource 抽象与定位权限 posture |
| v0.4 | 2026-06-15 | 定 D-5（BLE 链路参数设备驱动、app 只解包）；D-4 转向自研 TLV+protobuf 协议（不再外部阻塞）；新增 D-8/D-9/D-10；P2 改为实现真实解码器、P5 改为固件联调；§10 增协议约束 |
| v0.5 | 2026-06-15 | 定 D-8/D-9/D-10：混合编码+分片重组、F-CTRL-4 重定位（控制旋钮+观测、去硬闸）、Wire+.proto（固件自研同团队）；MTU 亦设备申请；标准头部+分包/组包写入 §10 |
| v0.6 | 2026-06-15 | 定 D-6：原始 HEX 行日志为主体（后期流式压缩）+ 解码按模块 CSV + 组合包兼容 CSV；每会话一文件夹；Sink 放宽为消息类型→1..N 文件；新增 F-FILE-6/7 |
| v0.7 | 2026-06-15 | 定 D-7：Android minSdk 29 / target 35、iOS 15+；记录简化红利（纯 MediaStore、去 java.time desugaring）与下游架构待同步项。**D-1～D-10 全部闭环** |
| v0.8 | 2026-06-15 | 产出协议 v0 草案：[BlueTrace_Protocol.md](Docs/architecture/BlueTrace_Protocol.md)（标准头部+分片+注册表+混合编码）+ [bluetrace_v0.proto](Docs/architecture/bluetrace_v0.proto)；§8.1 协议前置标记已产出 |
| v0.9 | 2026-06-15 | 协议过 5 视角评审并修订为 v0.1：pktSeq/batch_seq 语义、强制 hdrCrc8+框架错误处理、SampleFormat 宽度表+S24 符号扩展、sample_period_ns 消除小数率漂移、Goodix 双流独立描述、DeviceState/QueryState 重连对账、AlgoMetric 命名、ver/未知 msgType 强制语义（协议 §16 记修订全表） |
| v0.10 | 2026-06-16 | **收敛到 V4**：底部三 Tab（采集/数据/设置）定为唯一 IA、v3 向导式降为 legacy 参考；用户(F-SUBJ-*)、Wear/Unwear(F-MODE-*) 进一期；F-GPS-1 升一期实现；"离线收集"一期移除（在线/离线双模归二期）；删打包策略配置页、去带宽硬闸（对齐 D-5/D-9）；文件模型按 D-6 文件夹。配套 [V4 设计契约](Docs/reviews/BlueTrace_V4_设计契约_2026-06-16.md) 与 [设计系统 v2.0](Docs/product/BlueTrace_Design_System.md) |
| v0.11 | 2026-06-16 | **V4 精简（应用户反馈）**：设备改**扁平列表**（单击连接/断开 · DUT ≤3 + 参考心率带 ≤1 · 无角色槽）；传感器**纯开关**（透明传输 · 不控采样率 · 不外露高/低频）；采集运行**照竞品 Data Collection 重做**（设备卡 + 采集类型勾选 + 简单实时数据区 + Start/End 标签 + 暂停 + 长按 2 秒结束），移除波形⇄分包流双视图 / 只读观测 / 运行中控制面板（F-COL-2/3、F-CTRL-2/4/5 降**后期**）；异常压成**三态**（采集中 / 暂停重连 / 已停止）+ 运行日志（F-BG-3 不再红黄蓝分级、不单路降级）；**用户→用户**；权限为全局硬门。详见 [契约 §九](Docs/reviews/BlueTrace_V4_设计契约_2026-06-16.md) |
