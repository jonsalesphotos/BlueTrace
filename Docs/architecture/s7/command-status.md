# S7 · B2A 指令实现状态清单

> 依据：协议全表 [protocol-spec.md](protocol-spec.md) / [_raw/header.md](_raw/header.md)（b2a_protocol.h 逐值）；
> 实现：`shared/src/commonMain/kotlin/io/bluetrace/shared/s7/`（S7Console + S7Messages + S7Frame）。
> 更新：2026-07-02（真机 SKG WATCH S7-FCC4 联调后）。
>
> **本清单只覆盖「设备维护控制台」定位**——即 App 作为运维/调参工具需要的读写与设备控制指令。
> B2A 协议本身还含大量「手表业务」指令（推送来电/消息/天气、健康数据上报、闹钟/表盘/各类监测开关…），
> 这些属于**配套 App 的日常功能**，不在维护控制台范围，故整类标「业务类·不在本工具范围」。

## 一、传输层（已全部实现）

| 能力 | 状态 | 实现 |
|------|------|------|
| BLE 扫描（广播特征识别 FFE0，nRF 式全量展示） | ✅ | `AndroidBleClient.scan` + `B2aDetect` |
| 连接（MTU 247 → service discovery → 订阅 CCC） | ✅ | `AndroidBleClient.connect` |
| B2A 帧信封编解码（8B 头 + 4B 命令头 + CRC16-CCITT-FALSE） | ✅ | `S7Frame.kt` |
| 多包重组（index 连续性 + 声明长度校验） | ✅ | `S7FrameDecoder` |
| 短帧特例容忍（产测握手 uiLen<4） | ✅ | `S7FrameDecoder.singleFrame` |
| 单飞命令队列 + 3s 超时 + 迟到应答防错配 | ✅ | `S7Console.request` |
| 断链重连自动 decoder.reset | ✅ | `S7Console.start` 链路守卫 |

## 二、维护指令实现状态

### GET（读设备，0x02）

| Key | 指令 | 状态 | 控制台位置 |
|-----|------|------|-----------|
| 0x01 | GET_DEV_DATE_TIME 设备时间 | ✅ 已实现 | 时间栏 · 设备时间/偏差 |
| 0x04 | GET_PERSON_DATA 用户信息 | ✅ 已实现 | 用户信息栏 |
| 0x21 | GET_DEV_INFO 版本信息（TLV） | ✅ 已实现 | 设备信息栏 FW/Modem/SecBL/BP |
| 0x22 | GET_DEV_FUNC 功能掩码 | ✅ 已实现（真机回 0，逐位含义待固件） | 设备信息栏 Func |
| 0x23 | GET_DEV_BLE_MAC | ✅ 已实现（未单列，SN_INFO 已含 MAC） | — |
| 0x24 | GET_DEV_VOL 电量 | ✅ 已实现 | 设备信息栏 Battery |
| 0x26 | GET_SN_INFO 身份（SN/MAC/IMEI/ICCID） | ✅ 已实现（MAC 反序修正、DevType hex、ASCII 截断） | 设备信息栏 |
| 0x28 | GET_BOND 绑定状态 | ✅ 已实现 | 设备信息栏 Bond |
| 0x02/03/05..15/25/27/2A | 闹钟/目标/佩戴/单位/久坐/喝水/勿扰/心率·血氧·压力监测/各提醒/表盘/血压原始 | ⬜ 未做 · **业务类，不在本工具范围** | — |

### SET（写设备，0x03）

| Key | 指令 | 状态 | 控制台位置 |
|-----|------|------|-----------|
| 0x01 | SET 时间同步（对时） | ✅ 已实现 | 时间栏 · 同步时间 |
| 0x04 | SET_PERSON_DATA 用户信息 | ✅ 已实现（**可编辑写入** + 快捷写采集用户） | 用户信息栏 · 编辑并写入 |
| 其余 SET | 目标/提醒/监测开关/表盘… | ⬜ 未做 · **业务类，不在本工具范围** | — |

### DEV_CTRL（设备控制，0x07）

| Key | 指令 | 状态 | 控制台位置 |
|-----|------|------|-----------|
| 0x01 | POWER_OFF 关机 | ✅ 已实现（危险区 · 断链判据） | 危险操作 · 关机 |
| 0x02 | RESET 重启 | ✅ 已实现 | 危险操作 · 重启 |
| 0x03 | RESTORE 恢复出厂 | ✅ 已实现 | 危险操作 · 恢复出厂 |
| 0x04 | FIND 找手表 | ✅ 已实现（toggle 可停止） | 危险操作 · 找手表 |
| 0x05 | FIND_END 结束找表 | ✅ 已实现 | 找手表 toggle |
| 0x07 | FILE_LOG 拉取设备日志 | ✅ 已实现（→ Download/BlueTrace/logs/） | 设备日志栏 |
| 0x09 | ACK_FILE_LOG 日志回传块（设备发起） | ✅ 已实现（接收侧） | — |
| 0x06 | FIND_PHONE_END 找手机结束 | ⬜ 未做（手机侧被找，非维护） | — |
| 0x08 | BLE_LOG 实时日志 | ⛔ 固件 `#if 0` 未实现 | — |
| 0x0A/0x0B | FILE_ALGO 拉取/回传算法特征文件 | ⬜ 未做 · 后续（诊断增强） | — |

### IND（设备→App 指示，0x05）

| Key | 指令 | 状态 |
|-----|------|------|
| 0x0C | HEARTBEAT 心跳（带电量/seq） | ✅ 已实现（被动解析，头部显示） |
| 其余 IND（来电/运动/音乐/相机/健康/告警…） | ⬜ 不在本工具范围（业务类） |

### 未实现的**维护相关**指令族（除 OTA 外）

| 指令族 | 说明 | 为何暂缓 |
|--------|------|----------|
| **BOND 绑定族**（0x01：REQ/DEV/KEYCODE/DEL…） | B2A 业务绑定握手 + 鉴权码 | 真机实证**维护指令无需绑定**即可直发（completeness-audit §7）；KEYCODE 固件不校验。仅当未来固件加门控才需要 |
| **FILE_TRANS / OTA**（0x0F 全族） | 固件/资源 OTA 升级 | 端到端编排（文件清单/类型组合/生效触发）文档缺失，需固件组补升级包规范。**二期** |
| **FILE_ALGO**（DEV_CTRL 0x0A/0x0B） | 拉取算法特征文件 | 诊断增强，非核心维护，后续可加（复用日志拉取通道模式） |
| **GET_DIAL_INFO / 表盘管理**（GET 0x25 / SET 0x25-0x28） | 表盘信息/切换/排序/删除 | 业务类，非维护工具范围 |
| **TEST 工厂模式**（0x08） | 进入工厂模式 / 马达开关 | 产测专用；MOTO_ON/OFF 固件无 handler 未实现 |

## 三、一句话结论

**维护控制台需要的读写与控制指令已全部实现并真机验证**：设备信息全套 GET、时间/用户 SET、找手表/重启/关机/恢复出厂 DEV_CTRL、日志拉取、心跳被动解析。
**除 OTA（二期，等升级包规范）外，其余未实现项要么是业务类手表功能（不属维护工具），要么是固件侧 `#if 0` 未开（BLE_LOG），要么是可选诊断增强（算法特征文件）——均不阻塞控制台的运维用途。**
