# BlueTrace 第一版实现 · Agent 构建 Prompt（v1 · Mock BLE）

> 这份文档是给「实现 agent」用 **`/goal` 机制**驱动、产出 **BlueTrace 第一版 Android app** 的提示词。
> 第一版**用 Mock 造设备与数据**（真实通信协议还在设计、未冻结），先把其余部分全部做出来。
> 唯一实现依据：[`/SPEC.md`](../SPEC.md) + [`prototypes/v4_android.html`](prototypes/v4_android.html) + [`architecture/bluetrace_v0.proto`](architecture/bluetrace_v0.proto)。
> **忽略仓库里现有的 `app/`**（那是未跟踪的 demo，不参考、可整体覆盖重写）。

---

## 一、怎么用

在实现 agent 的会话里执行（把下面这行作为 `/goal` 的条件）：

```
/goal 依据 Docs/agent_build_prompt_v1.md 的 Brief 与验收清单，从零实现 BlueTrace 第一版 Android app（KMP 结构，BLE 用 Mock 造设备与数据，真实协议解码先不做），直到「验收清单」全部满足。先通读 SPEC.md + Docs/prototypes/v4_android.html，再按 P1→P4 增量实现并自测。
```

`/goal` 会挂一个会话级 Stop 钩子：agent 把该条件当作指令，持续迭代直到验收清单达成才停。

---

## 二、Brief（实现纲要）

### 0. 目标
依据 SPEC + 原型从零实现 **BlueTrace 第一版 Android app**：KMP 结构、Android 优先；**BLE 用 Mock**（造设备 + 持续造数据），真实协议解码留接口先不做；其余按 V4 设计全部实现并能跑通闭环。

### 1. 工程结构（SPEC §10.1 / §10.2 / D-V4-19）
- **KMP 两模块**：
  - `:shared`（commonMain，纯 Kotlin、无 Android 依赖、可 JVM 单测）：`protocol/`（帧/解码接口，第一版留桩）、`session/`（采集会话状态机 + `SessionController` 接口）、`data/`（raw HEX & CSV 的 okio append 写入器、D-6 目录布局、manifest 模型 kotlinx.serialization、unix 时间工具）、`domain/`（扁平设备/Subject/Mode/Session 实体 + repository 接口）、`ble/`（`BleClient` 接口）。
  - `:app`（Android）：Compose UI（按原型逐屏）、`BleClient` 实现、前台服务、MediaStore 导出、权限/蓝牙门控、DataStore、Koin。
- **技术栈**：Kotlin 2.2.x / AGP 9.x / Compose + Material3 **adaptive（NavigationSuiteScaffold）** / **Navigation Compose 类型安全（`@Serializable` 路由）** / **Koin** / **okio** / **kotlinx.serialization** / **DataStore** / kotlinx.coroutines。SDK：minSdk **29** / target·compile **36**。

### 2. BLE = Mock（第一版核心）
- 在 commonMain 定义 **`BleClient` 接口**：扫描、连接、断开、订阅 Notify、写、连接状态流（Flow）。
- 提供 **`MockBleClient`**（放 commonMain 便于 JVM 单测；用 coroutines 定时 emit）：
  - **扫描**：产出若干假 DUT（`BT-DUT-0427` / `BT-DUT-0319` …，RSSI 随机波动）+ 1 个假标准心率带（`Polar H10`，HRS `0x180D`，♥ 跳动）；可演示「无结果空态」。
  - **连接后持续造数据**（按节流）：模拟高频批包 → 同时产出 **① 原始 `[unixMs] HEX` 行**（喂给 raw HEX 写入器）+ **② 已"解码"的假样本**（ppg/acc 数值流、HR 值），驱动采集运行的实时流、Datas/Time、设备卡 HR。
  - 可**注入断联事件**（演示「断联 → 内联重连中 → 自动续」），可**模拟存储满**（演示自动结束）。
- **真实协议解码第一版不做**：`protocol/` 解码留接口/桩；Mock 直接给"原始 HEX 行 + 已解码样本"，**跳过 Wire 解析**。接口设计要让日后换上真实解码实现即可，不改上层。

### 3. 第一版要做（逐屏 + 行为，全部对照原型与 SPEC）
- **启动屏**（一闪而过）→ **权限门控**（首启请求 / 后续静默检查 / 可跳过 / 按需就地请求 / 永久拒绝引导系统设置 / **蓝牙开关检测** / 后台省电指南入口）→ **三 Tab（采集 / 数据 / 设置）**。
- **设备连接**：扁平列表 **DUT ≤3 + 参考 ≤1**、单击连/再点断、连接计数、RSSI 过滤滑条、扫描 60s 自停（mock 计时）、连接上限禁用、无结果空态。
- **用户**：选择 / 新建编辑（Subject：别名/性别/生日/身高/体重，本地存储）；**采集模式 Wear/Unwear**。
- **采集运行**（§5.4 / §5.5 / §5.6）：设备卡（含实时 HR）、**采集类型选择**（下抽屉，开关=该路是否上传/落盘）、**实时流 `[unixMs] HEX` 每条一行·锚定底部·无滚动条**、Datas/Time、**Pin 瞬时点 + Start/Stop 区间标签**、**暂停**（仅停数据框滚动）、**硬锁定**（app 内不可退）、**长按 2 秒结束**；断联→设备卡内联「重连中」自动续；存储满→自动结束。
- **结束摘要** → 落地 **D-6 会话文件夹**（§6）：`raw/*.hexlog`（source of truth）+ 每模块解码 CSV + `session_manifest.json`（**unix 起点 + 时区 + 用户/模式/设备 + 质量小结：重连次数/断联时长/丢包**）+ 可选 `gps.csv`。
- **导出**：整文件夹经 **MediaStore** 写 `Download/BlueTrace/`，进度 + 完成 Toast + 失败/空间不足处理。
- **数据 Tab**：会话列表（按日期分组 + Wear/Unwear 筛选 + 搜索）、多选/批量导出删除、会话详情（manifest 摘要 + 逐项可选导出）、空态/搜索无果。
- **设置**：环境与权限检查、**GNSS**（可选一路，while-in-use）、导出位置、存储占用、**应用日志**（运行错误/事件，可导出/清空，非开发调试日志）、**设备维护(DUT)** 占位、关于。
- **后台 / 恢复**（§5.10 / §7.4）：前台服务托管采集（常驻通知作在场感）；进程恢复 = 服务活则重绑续采 / 全杀则**开口会话自动收尾**（`stopReason=interrupted` + toast）。
- **时间**：全程 **unix 时间戳**；文件名 `<Mode>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>`。

### 4. 第一版不做（明确跳过）
- 真实协议解码（Mock 替代，留接口）；
- 传感器总控 / 设备端算法（配置A/B，暂不实现）；
- 设备维护具体功能（对时/写用户信息/读固件日志/OTA，占位即可）；
- iOS；服务器 / 上传 / 透传 / 远程下发（二期）。

### 5. 必须遵守的口径（钉住 V4，别走 v3 老路）
- 设备是**扁平列表 + 限额**，**不是** `Map<Role,Device>`（每角色一台）；Device 按 `kind` 分（自研多传感器 DUT / 标准 HRS 心率带）。
- 采集运行**硬锁定**（无 app 内"正在采集 Banner"，在场感靠前台服务通知）。
- commonMain **零 Android 依赖**；BLE 全在 `BleClient` 接口后（`MockBleClient` 可一键换真实实现）；`SessionController` 用接口 + Fake/Mock 实现（非临时 mock）。
- UI 视觉/交互以 `v4_android.html` 逐屏（含 `.screen-ux`）为准；不复述、不臆造。

### 6. 工作方式
- 先**通读** SPEC + 原型，列实现计划（可用 TaskCreate 跟踪）。
- 按 **P1→P4** 增量（P5 真机协议联调跳过）：P1 KMP 骨架 + 权限/扫描 + 三 Tab 导航 + `BleClient`/Mock 跑通；P2 扁平设备/采集类型/三态状态机；P3 D-6 落盘/导出/GNSS；P4 前台服务/进程恢复。
- 每完成一块在**模拟器**跑或用 Compose Preview 自测；commonMain 逻辑写 **JVM 单测**。
- 提交遵循仓库习惯（直接推 main、约定式提交信息）；偏离 SPEC 处在提交说明里列出并说明原因。

---

## 三、验收清单（Definition of Done · 即 `/goal` 达成条件）

1. **构建**：`./gradlew :app:assembleDebug` 成功；`:shared` 的 JVM 单测全绿（至少覆盖：状态机三态、D-6 写入器、manifest 生成、MockBleClient 产数据）。
2. **闭环可跑**（模拟器）：启动 → 权限 → 三 Tab；扫描见 mock 设备并连接（计数/上限生效）；选用户 + 模式；开始采集 → 运行页**实时流滚动（`[unixMs] HEX`）**、Datas/Time 递增、HR 跳动、可打 Pin/区间标签、**硬锁定生效**（返回拦截）；长按 2 秒 → 结束摘要 → 数据 Tab 出现该会话。
3. **数据落地**：会话为 **D-6 文件夹**（raw HEX + 解码 CSV + `session_manifest.json` 含 unix 起点 / 时区 / 质量小结）；**导出** 到 `Download/BlueTrace/` 成功（Toast）。
4. **异常演示**：注入断联 → 内联「重连中」→ 自动续写不丢数据；模拟存储满 → 自动结束并保存；杀进程重启 → 开口会话自动收尾（toast）。
5. **结构合规**：commonMain 无 Android 依赖；BLE 在 `BleClient` 接口后、`MockBleClient` 可换真实实现；技术栈符合 §10.2；UI 与原型/ SPEC 一致。
6. **跳过项**未实现但**入口/占位**齐（配置A/B、设备维护、GNSS 可选），且不阻断主流程。

> 全部满足即第一版完成。后续：协议冻结后用真实 `BleClient` + Wire 解码替换 Mock（接口不变），再做 iOS。
