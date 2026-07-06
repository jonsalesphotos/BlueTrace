# S7 · B2A 协议文档完整性审计报告

> 审计对象：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议`（14 文件，约 7300 行 MD/头文件 + 2 个大 HTML）。
> 方法：6 路并行深读提取 → 头文件×手册交叉校验 + 10 项完整性审计 + 矛盾猎手（9 代理）。
> 原始报告：[_raw/crossCheck.md](_raw/crossCheck.md)、[_raw/completeness.md](_raw/completeness.md)、[_raw/contradictions.md](_raw/contradictions.md)。

## 总体判定

**约七成信息可直接写代码，足以支撑「设备维护控制台」一期落地。**
传输层（GATT UUID 全套）、帧信封（8B+4B、CRC16-CCITT-FALSE 参数与覆盖范围、分包位定义）、错误码全表**多源一致、零冲突**；时间/设备信息/SN/电量/用户信息/重启恢复出厂等维护命令有逐字段偏移表。
硬缺口集中在可靠性约定、日志 EOF、OTA 编排、鉴权门控、golden 样例五处（见下）。

## 十项审计结论

| # | 方面 | 判定 | 要点 |
|---|------|------|------|
| 1 | GATT UUID 全套 | ✅ complete | FFE0/FFE1(WriteNoRsp)/FFE2(Notify)/CCC 齐备，可直接落码 |
| 2 | 帧信封 | ✅ complete | 三源一致；全套材料中最扎实部分 |
| 3 | 请求-应答配对/超时重传 | ⚠️ partial | 配对=回显同 Cmd/Key + EHST_ACK 门控；**超时/重传零定义**；无事务 ID；重启类命令不回包 → App 自建单飞队列 + 断链判据 |
| 4 | 分包/重组 | ⚠️ partial | 上行重组规则完整；**单包上限值未给**（日志路径反推 ≈237B）；下行分片尺寸无定义 → 控制台命令全部 ≤20B 规避 |
| 5 | 命令表完备性 | ⚠️ partial | 维护命令约九成可直接编码；**MAC/IMEI/ICCID 字节序待核对**；GET_DEV_FUNC 32 位掩码逐位含义缺失；szPassthru 语义未定 |
| 6 | 错误码表 | ✅ complete | EBEC 0x00–0x0D 全量 + EHST 状态位 + CommAck 语义 |
| 7 | 绑定/鉴权前置 | ❌ contradictory | 配对 LESC 与否两文档矛盾（对 App 影响小）；**维护命令是否要求先 B2A 绑定：零说明**（KEYCODE 的 16B Code 固件不校验）→ 首轮实机验证「未绑定直发 GET_DEV_INFO」 |
| 8 | OTA 状态机 | ⚠️ partial | FILE_TRANS 五子命令字段完整；**端到端编排缺失**（文件清单/类型组合/生效触发）→ 一期不做 OTA，仅保留入口灰显 |
| 9 | 日志拉取 | ⚠️ partial | 触发/块结构明确；**无 EOF、块无序号** → 空闲超时启发式 + 「可能不完整」提示 |
| 10 | golden 报文 | ⚠️ partial | 全部材料仅 1 条完整帧（产测握手，CRC 已独立复算吻合）；命令级 golden 需实机抓包 |

## 交叉校验结论（头文件 vs 手册）

- **协议骨架零冲突**：帧头、命令头、状态位、CRC、错误码、10 个一级命令与全部双覆盖子键逐值一致（同源 `ble2appEx.h`/`ble2appWrap.c`）；
- 实际不一致仅 5 处，全部为**文档粒度/命名级**，无线上字节格式矛盾；
- **重要发现**：产测握手帧 uiLen=3 违反「首包必带 4B 命令头」通则且 CRC 自洽 → 解码器必须容忍短帧；
- `b2a_protocol.h` 是派生摘要而非原始源码，冲突时以「`ble2appEx.h/ble2appWrap.c` 行号引证密度更高的一方」为准。

## 五处文档矛盾（已消歧）

| 矛盾 | 消歧结论 |
|------|----------|
| 配对 LESC 与否 | 按 Just Works + Bond 实现，OS 层处理，App 无感 |
| MTU 协商机制（AttcMtuReq vs 服务消息） | App 只消费协商结果，不主动请求 |
| GH3X2X 服务 UUID 128/16-bit | 生效 16-bit；该工厂服务与维护控制台无关 |
| GH3X2X RX 属性 | WriteNoRsp\|Write；同上无关 |
| 服务集合（是否含 ANCS/HID/DIS） | 以实际编译开关收敛：仅 AMDTP + GH3X2X 两组 |

## 需固件组/实机补齐清单（真机首轮实证更新 2026-07-02 晚，SKG WATCH S7-FCC4 · FW 1.0.102）

- [x] **未绑定直发维护命令：不被拒**——GET 全套/SET 对时/日志拉取直接成功，无任何绑定/鉴权前置
- [x] **MAC 字节序：SN_INFO.BleMac 为 LE 反序**（线上 C4:FC:19:48:61:71 = 实际 71:61:48:19:FC:C4）——解析已修复为反转展示
- [x] **DevType[5] = 二进制 device_type 码**（真表 `68 39 71 25 80`，结尾 0x80=国内版，对上文档 device_type 表）——hex 展示
- [x] **日志拉取真机实测可用**：now+bak 拉回 431,913 B / 1938 块 / 实测吞吐 ≈28 KB/s；空闲 4s 判完成工作正常（尾块可截断，与协议无 EOF 的已知局限一致）
- [x] IMEI/FW 等 ASCII 段尾部含非打印填充——解析已加截断
- [ ] GET_DEV_FUNC 掩码：真表返回 **0x00000000**（全 0，逐位含义仍缺，且是否恒 0 待固件确认）
- [ ] **新发现**：GET_DEV_VOL.uiCapacity 疑似回传电量百分比而非 mAh（75%→75、91%→91、97%→97 跟随变化）——字段语义待固件核对
- [ ] FILE_LOG szPassthru 语义 + 日志两文件边界样本
- [ ] OTA 升级包规范（文件清单、ucModuleId×ucType 组合、生效触发）
- [ ] 各维护命令实机抓包 → 补 golden bytes 测试基线（可从操作日志 TX/RX hex 摘取）
- [ ] 单包上限 iMaxParamPktLen 实值、下行分片设备缓冲承受度

## 真机联调工程备忘（Android 侧坑）· 已网络核查（AOSP 源码/官方文档，2026-07-02）

> 四条结论用工作流上网核实：2 条**证实**、2 条**部分正确**（原表述有偏差，下已修正）。完整证据/来源见文末。

- **① 定位权限缺失 → 扫描静默过滤**〔**证实**〕：Android 12+ 未声明 `neverForLocation` 且 App 无 `ACCESS_FINE_LOCATION`（或系统定位总开关关闭）时，BLE 扫描结果被系统**逐条丢弃**，`startScan` 正常返回、`onScanFailed` 不触发、`onScanResult` 永不来。证据 = AOSP `GattService.hasScanResultPermission` + `Utils.blockedByLocationOff`（android12-release）。**修正**：官方文档并未明写该静默行为（仅源码可证）；且 logcat 侧其实**有** `Permission denial: Need ACCESS_FINE_LOCATION permission to get scan results` 错误日志——「对回调静默，对日志不静默」。系统定位总开关同样致空结果。
- **② 权限重装重置**〔**部分正确 · 原归因需修正**〕：观察到 `installDebug` 后 ADB 授予的 `FINE_LOCATION` 变 `denied`/appops `ignore` 属实，对策（重装后 `pm grant` + `appops set … allow`，或 `install -r -g`）有效。但**机制归因要改**：COARSE 被重装撤销是 **AOSP 通用机制**（`REVOKE_WHEN_REQUESTED` + FINE→COARSE 隐式 split-permission，见 `PermissionManagerServiceImpl` / `platform.xml`），**不是 MIUI 特有**；appops 变 `ignore` 是权限被撤后 AOSP `PermissionPolicyService` 的标准联动，也非 MIUI 独立动作。「FINE 重装即被重置」在 AOSP 无对应路径、疑似 MIUI 行为，但**缺公开逐字实证**——属「本机实测 + 合理推断」，不宜称「MIUI 已知行为」。
- **③ 扫描节流 5 次/30 秒**〔**部分正确**〕：阈值属实（AOSP `NUM_SCAN_DURATIONS_KEPT=5` / `EXCESSIVE_SCANNING_PERIOD_MS=30s`，当前 main 已改 DeviceConfig 可配 `scan_quota_count/window`）；「静默拒绝」属实且更绝对——**即便 API 33 公开了 `SCAN_FAILED_SCANNING_TOO_FREQUENTLY=6`，AOSP 框架仍故意吞掉、`onScanFailed` 收不到 6**（原表述「可能回调 errorCode=6」**是错的**）。恢复窗口 ≤30s。联调对策：避免 30s 内快速反复 start/stop 扫描（本次联调多次踩中）。
- **④ GATT 取消泄漏**〔**证实**，已修复〕：`connect` 协程被取消（页面返回）时未 `disconnect()`+`close()` 会泄漏——`BluetoothGatt.close()` 内部才 `unregisterApp()` 释放 client 接口，**无 GC 兜底**（官方 javadoc）；client 上限 `GATT_MAX_APPS=32`，且外设被连接后**停止广播**（BLE 固件默认），他人扫不到直到 App 进程被杀。修复：`AndroidBleClient.connect` 的 `CancellationException` 分支强制清理。

**核查来源**：AOSP `GattService.java`/`Utils.java`(android12-release)、`PermissionManagerServiceImpl`/`platform.xml`/`PermissionPolicyService`(main)、`AppScanStats.java`(nougat-release)、`BluetoothGatt.java`(main)；`developer.android.com` 蓝牙权限页 / `ScanCallback#SCAN_FAILED_SCANNING_TOO_FREQUENTLY` / GATT 连接指南；Punch Through、AltBeacon、Nordic DevZone、Martijn van Welie(BLESSED) 等社区佐证。
