# BlueTrace 里程碑与进度

> 状态标记：✅ 完成（已真机 / 单测验证）· 🔄 进行中 · ⬜ 待办 · ⏸ 暂不实现（一期范围外）
> 真源：[`/SPEC.md`](../SPEC.md)（§10.5 P1–P5）+ [`prototypes/v4_android.html`](prototypes/v4_android.html)。最后更新：2026-06-22。
>
> **一句话现状**：一期 Android app（KMP，`io.bluetrace`）**核心闭环全部打通并真机验证**——P1–P4 完成、产品化打磨完成；**唯一未完成的是 P5（真实 DUT 协议解码），卡在通信协议冻结**。BLE/DUT 仍走 Mock；iOS 与服务器属二期。

---

## 阶段里程碑

### ✅ M1 · KMP 骨架 + 闭环（P1–P2）
- KMP 两模块 `:shared`(commonMain，纯 Kotlin) + `:app`(Android)；Compose + Material3 adaptive、类型安全 Navigation（三 Tab 各独立嵌套 NavGraph + 返回栈）、Koin、DataStore。
- **Mock BLE**（`BleClient` 接口 + `MockBleClient`）：造假 DUT + 标准心率带 + 持续造数据。
- **扁平设备 + 限额**（DUT≤3 + 参考≤1，非 `Map<Role>`）、用户(Subject) CRUD、Wear/Unwear 模式。
- 三态采集运行状态机（就绪/采集中/已停止）、采集类型选择、硬锁定 + 长按 2 秒结束、实时流 `[unixMs] HEX`、Pin/Start-Stop 标签、暂停=仅停滚动。

### ✅ M2 · 数据落地 + 导出（P3）
- **D-6 会话文件夹**：raw HEX(source of truth) + 每模块解码 CSV + 组合包 CSV + `session_manifest.json`（unix 起点 + 时区 + 质量小结）+ `gps.csv`。
- **MediaStore 导出** → `Download/BlueTrace/`（免存储权限）；数据 Tab 列表/详情/多选/搜索。
- **本机 GNSS（F-GPS-1）**：真实 `LocationManager`（while-in-use，不申请后台定位 D-3）→ `gps.csv`，**真机实测真实经纬度**。

### ✅ M3 · 后台 / 进程恢复（P4）
- 前台服务托管采集（`connectedDevice|location|dataSync` + 常驻通知作在场感）。
- 进程恢复：服务活则续采 / 全杀则开口会话自动收尾（toast）；存储预检全链（开始前 + 导出前）；权限永久拒绝 `BLOCKED`→引导设置；采集中关蓝牙广播→暂停·自动重连。

### ✅ M4 · 产品化打磨（本会话主力）
- **品牌图标 + 启动屏**：自适应启动器图标 + Android 12 SplashScreen；**两层启动屏**（系统层只画底色、应用内 `AppSplash` 承载渐变 logo + 字标 + 副标 + 三点）合成「一个开屏」；冷启动仅一次。
- **全局外观模式**：亮 / 暗 / 跟随系统（设置→外观），DataStore 持久化、即时生效、可覆盖系统；深色双色板 + 系统栏图标随模式。
- **GNSS 入口迁移（Q1）**：从设置开关 → **采集类型勾选**（每会话 + 就地授权），设置页 GNSS 屏/入口已整屏移除。
- i18n（zh 默认 + values-en + plurals）、edge-to-edge 系统栏、演示钩子 DEBUG 门控。
- 🔄 **设计稿↔真机逐屏对照收敛**（v3）：已收敛 采集A/用户A/设备A/设置 等；其余屏按 [`agent_build_prompt_v3.md`](agent_build_prompt_v3.md) 继续。

### ⬜ M5 · 真实 DUT 协议解码（P5）—— **当前唯一硬缺口**
- **阻塞**：`architecture/bluetrace_v0.proto` 仍 **v0.1 草案，待与固件端冻结**（§4 / 协议 §15）。
- 冻结后：换真实 BLE(Nordic Kotlin-BLE) + Wire 解码替换 Mock（`BleClient`/`SampleDecoder` 接口不变，上层 UI 不动）；标准心率带(HRS 0x180D)不依赖冻结、可先上真实 BLE。
- 产出冻结清单交固件团队（待办）。

### ⬜ M6 · iOS（二期）
- KMP `:shared` 已为 iOS 预留（纯 Kotlin、可 JVM 单测）；iOS app 未开工。

### ⬜ M7 · 服务器 / 上传 / 远程下发（二期）
- 透传上传、`dataSync` 前台服务类型、远程下发；当前设置页"服务器同步"灰显占位。

### ⏸ 暂不实现（一期范围外）
- 配置A/B（传感器总控 / 设备端算法）—— 原型标暂不实现，入口/占位在文档末。
- 设备维护(DUT) 具体功能（对时 / 写用户信息 / 读固件日志 / OTA）—— 设置页占位屏，子项灰显。

---

## 质量 / 验证状态
- **构建**：`./gradlew :app:assembleDebug` ✅；`:shared:jvmTest` ✅（**26 个 commonMain JVM 单测 / 7 文件**：状态机、D-6 写入器、manifest、MockBleClient、GNSS 落盘、会话命名 / 时间格式等）。
- **真机**：Xiaomi M2101K9C / Android 13 / MIUI 实测——扫描(Mock)→连接→采集→断联重连→存储满自动结束→结束摘要→数据→导出闭环；启动屏（冷启一次、深浅色）、全局主题三档、GNSS 勾选→`gps.csv` 真实写入，均通过。
- **参照集**：[`assets/screenshots/`](assets/screenshots/)（设计稿 45 屏）+ [`assets/screenshots_device/`](assets/screenshots_device/)（真机实拍）。

## 下一步（建议优先级）
1. **推动协议冻结**（解锁 M5）：出 `.proto` 冻结清单给固件；冻结前可先用标准心率带跑真实 BLE 链路。
2. **完成 v3 设计收敛**（M4 🔄）：剩余屏逐屏对照真机，冲突先确认。
3. M5 落地后再起 iOS(M6) / 服务器(M7)。
