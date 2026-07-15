# S7 设备维护（DUT）控制台 · 实施计划表

> 依据：[protocol-spec.md](../../S7B2A/protocol-spec.md)（实现规格）+ [completeness-audit.md](../../S7B2A/completeness-audit.md)（缺口边界）。
> 原则：逻辑全下沉 `shared` commonMain（KMP 就绪，遵循 architecture-v2/04 号规划）；UI 工程风格不做华丽；
> OTA 一期**不做**（端到端编排文档缺失），入口灰显保留；日志拉取按空闲超时启发式实现并明示局限。

## 范围（一期）

| 功能块 | 协议 | 取舍 |
|--------|------|------|
| 设备信息（版本 TLV / SN / MAC / IMEI / ICCID / 功能掩码 / 绑定状态） | GET 0x21/0x26/0x23/0x22/0x28 | ✅ 全做；身份字段按原序 hex 展示并注明「字节序待核对」 |
| 时间对时（读设备时间→显示偏差→一键同步） | GET/SET 0x01 | ✅ |
| 电量（容量/电压/百分比/充电态 + 心跳被动更新） | GET 0x24 + IND 0x0C | ✅ |
| 用户信息（读 + 写入当前采集用户） | GET/SET 0x04 | ✅ |
| 找手表（开始/结束） | DEV_CTRL 0x04/0x05 | ✅ |
| 设备日志拉取（落文件） | DEV_CTRL 0x07→0x09 | ✅ 空闲超时 4s 判完成 + 「可能不完整」提示 |
| 重启 / 关机 / 恢复出厂 | DEV_CTRL 0x02/0x01/0x03 | ✅ 危险区二次确认；**断链=成功**判据 |
| OTA / 表盘 / 算法特征文件 | FILE_TRANS / 0x0A | ⛔ 二期（等升级包规范） |
| 操作日志面板（每条命令的 TX/RX hex 与结果） | — | ✅ 工程调试刚需 |

## 改动清单（按依赖顺序）

| # | 文件 | 改动 | 验收 |
|---|------|------|------|
| P1 | `shared/.../ble/BleClient.kt` | 接口加 `suspend fun write(deviceId: String, bytes: ByteArray)`（下行命令通道） | 编译过；Mock 实现 |
| P2 | `shared/.../s7/S7Frame.kt` **新增** | B2A 信封编解码：CRC16-CCITT-FALSE、8B 头 + 4B 命令头编码、流式解码（含短帧特例容忍、多包重组、CRC 校验失败计数） | 单测：golden 帧 `BB 02 03 00 2D 46 00 00 08 01 01` 解码 + CRC 复算 0x462D + 编解码 roundtrip + 多包重组 |
| P3 | `shared/.../s7/S7Messages.kt` **新增** | 命令/键常量、错误码表、维护命令 payload 编解码（时间 9B、电量 10B、SN 59B、用户 7/8B、设备信息 TLV、心跳 8B），全部带越界防御 | 单测：逐消息编解码 + 畸形输入不抛 |
| P4 | `shared/.../s7/S7MockWatch.kt` **新增** | 设备侧模拟器（commonMain）：收 write 帧 → 按协议回应答帧（GET 全套/SET CommAck/找表/日志 3 块/重启断链），既当 Mock 数据源又当测试夹具 | 单测：经 S7Console 全命令闭环 |
| P5 | `shared/.../s7/S7Console.kt` **新增** | 控制会话：单飞命令队列（Mutex + 3s 超时）、应答按 Cmd/Key 配对、心跳被动解析、日志拉取 Flow（空闲超时收束）、断链判据命令（10s 观察 LinkState）、操作日志 SharedFlow | 单测：超时、乱序上报不干扰配对、断链判据 |
| P6 | `shared/.../ble/MockBleClient.kt` | roster 加 `SKG WATCH S7-0D10`（PROFILE_S7）；实现 `write()` 路由至 S7MockWatch；notifications() 合流 mock 应答帧；其余设备 write 为 no-op | 扫描页出现 S7 设备可连接 |
| P7 | `shared/.../domain/Device.kt` | 加 `PROFILE_S7 = "SKG.S7.B2A"` 常量 | — |
| P8 | `app/.../viewmodel/DeviceConsoleViewModel.kt` **新增** | 从 ConnectionRegistry.connected 取 PROFILE_S7 设备；持有 S7Console；暴露 UiState（设备信息/时间/电量/用户/日志进度/操作日志）；日志落 `logs/s7_devlog_<ts>.log` | — |
| P9 | `app/.../ui/screen/settings/DeviceConsoleScreen.kt` **新增**；`SettingsScreens.kt` 删旧占位 | 工程风格控制台：未连接态（引导去设备连接页）+ 信息区（等宽字体键值行）+ 操作区（按钮行）+ 危险区（确认对话框）+ 操作日志面板（monospace 滚动） | 实机截图 |
| P10 | `app/.../ui/BlueTraceApp.kt` | DeviceMaintenance 路由指向新屏 | 导航可达 |
| P11 | `app/.../di/AppModule.kt` | 注册 DeviceConsoleViewModel | — |
| P12 | `strings.xml`（zh + en） | 控制台全部文案（复用既有 maint_* 前缀扩展） | 双语言编译过 |
| P13 | `shared/src/commonTest/.../s7/` **新增** | P2–P5 的全部单测 | `:shared` 测试全绿 |
| P14 | 构建/验证 | `gradlew :shared:jvmTest :app:assembleDebug` → installDebug → ADB 实机截图（未连接态/已连接全数据态/日志拉取/危险确认） | 截图归档 `Docs/architecture-v2/s7/assets/` |
| P15 | 质量收口 | 多代理评审 diff（正确性 + 协议一致性对照 protocol-spec.md）→ 修复 | 评审 0 未处理 CONFIRMED |

## 风险与对策（继承审计结论）

| 风险 | 对策 |
|------|------|
| 鉴权门控未知（真机可能拒未绑定命令） | Mock 先行打通全链路；真机首测清单已在 audit「补齐清单」 |
| 日志无 EOF | 空闲超时 4s + UI 明示「可能不完整」+ 字节数展示 |
| 重启类不回包 | 「已发送，等待设备断开…」状态 + LinkState 断链判成功 |
| MAC/IMEI/ICCID 字节序 | 原序 hex 展示 + 界面标注待核对 |
| 真机 GATT 未接（BleClient 仍 Mock） | S7Console 只依赖 BleClient 接口；换 Nordic 实现时控制台零改动（02 号架构 R4 路线） |

## 增补（2026-07-02 下午 · 真实蓝牙阶段）

| # | 内容 | 状态 |
|---|------|------|
| P16 | `AndroidBleClient`（app/data/android）：真实 GATT——全量扫描提取广播 UUID、connectGatt→MTU 247→发现服务→按 B2A(FFE1/FFE2)/HRS(2A37) 订阅 CCC、WriteNoRsp、设备级持久通知流 | ✅ |
| P17 | `CompositeBleClient`：真实扫描 ∪ Mock BT-DUT（剔除 Mock S7 防与真表混淆）；按 id 形态路由（MAC=真实）；`toggleConnect` 改「先连后入册」防幽灵条目 | ✅ |
| P18 | 控制台内置连接页 `ConsoleConnect`（**非参考设备限连 1 台**，连新自动断旧；参考设备不展示）+ 未连接态「去连接手表」+ **多设备时先选择**（选择列表 + 当前控制 chips 切换，黏性选择） | ✅ 实机验证（选择/切换/超时表现全通过） |
| P19 | 真表联调（`SKG WATCH S7-FCC4`，S7_TEST_MAC，FW 1.0.102） | ✅ **全通**：广播识别（FFE0 徽章）→ 连接（GATT+MTU+CCC）→ GET 全套真实解码（SN/IMEI/ICCID/版本/电量/时间/用户）→ 拉真实日志 431KB/1938 块 ≈28KB/s → 对时 +0s。鉴权门控实证=无；MAC 反序/DevType 码/非打印填充三处解析已按实证修正；环境中另有 VitaPilot-D8F7、S7-FC9A 两台 B2A 设备同样被识别（不限一款达成）。工程坑与字段疑点记录于 completeness-audit.md |
| P20 | 纯真实扫描（去 Mock 合流，用户定）+ nRF 式全量展示（含无名设备） | ✅ |
