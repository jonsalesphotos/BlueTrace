# S7 采集固件 OTA · 设计与实现计划

> **状态**：重新构思期新增（2026-07-07）。定位 = **方向 B：实验室 attended 配置少量表**。真机可联调。
> **真源**：现网 B2A 协议 = [`../S7B2A/S7协议共识规格.md`](../S7B2A/S7协议共识规格.md)；本文 FILE_TRANS 字段由固件 `apollo4_watch_s7` 反推（file:line 在册），**字节级偏移/字节序待真机抓包最终验证**（全仓唯一 golden 帧是产测握手，OTA 各帧无真机报文）。
> **一句话目标**：在 BlueTrace 里做一个受控的"把常规 S7 表刷成采集表"的 attended 流程——常规表没有采集能力（采集是编译期宏 `SYS_DATA_COLLECT_SVC`，常规固件里根本没这段代码），只能整包换成采集固件。

---

## 0. 定位与边界

- **为什么**：常规 S7 手表 BLE 栈能连、能控、能拉日志，但**不能采集**（采集固件是另一条构建分支）。要采集必须先 OTA 刷入采集固件。
- **方向 B（用户 2026-07-07 拍板）**：受控 attended——你/技术人员盯着，充电+贴手机+亮屏，一次性把手头少量表刷成采集表。砖了有 J-Link 有线兜底。
- **不做**：消费级后台无人值守、跨进程杀死的健壮续传、非对称签名、全 ModuleId 通用刷写。
- **砖化姿态（已大幅下降）**：BLE OTA **永不碰 SecBL/boot**（见 §1.3），砖必落在应用/资源区，`flash_fw.ps1`（J-Link SWD）**无条件可救**。风险从"高+不可恢复"降到"中+可有线恢复"。

---

## 1. 关键事实（真实包 + 固件坐实）

### 1.1 升级包 = 已知（不再"文档缺失"）

真实 OTA 包 `E:\1\apollo4_watch_s7\out\product\apollo_eiot_watch\` 已坐实，采集包**已预构建**（`apollo4_watch_s7_collect/out/.../`）——**不用自己 build**。

构建产三种 zip（`CMakeLists.txt:1559-1561`）：

| zip | 文件 | 用途 |
|---|---|---|
| 整包 `*_ota.zip` | 19 项（含 `boot.bin`/`SecBL.dat`/`ble_fw.dat`/`apollo_eiot_watch.bin`/`FileList.dat`/`FormatExtFlash`） | **有线/工厂刷写器**（J-Link 引导路径），**不走 BLE** |
| `*_ota_all.zip` | 14 文件（字库 + Res 三件套 + `fw.dat`） | BLE OTA · 全量 |
| `*_ota_part.zip` | 4 文件（`ResData` + `ResFat` + `ResCheck` + `fw.dat`） | BLE OTA · 差分（省掉字库） |

**最小必推集 = `ota_part` 四文件**（固件 `OTA_DoRecvAfter` 只对 Res 三件套做存在性校验、缺失即失败无补拷，`OTA_Main.c:640-676`；字库 `fCheck.dat`+9 个 `fCN*/fNum*` 缺失时能从旧 A/B 区补拷，`:682-772`）。

**采集包各文件大小**（stock vs 采集逐文件对比，几乎全不同——只有 `fNum*` 数字字库相同）：

| 文件 | 采集大小 | 说明 |
|---|---|---|
| `ResData.dat` | 18,136,416 | 资源大 blob（占大头） |
| `fw.dat` | 1,613,656 | 采集主 MCU 镜像（裸 ARM 向量表，无自定义头） |
| `ResFat.dat` / `ResCheck.dat` | 2,944 / 4,440 | 资源 FAT + per-file 校验表 |
| **ota_part 合计** | **≈ 19.76 MB** | **最小必推（字库走补拷=旧表字库）** |
| + 字库(`fCN26/34/40`,`fCheck`) | +≈ 5.4 MB | ota_all（装采集自己的字库，视觉正确） |

**传输时长估**：~19.7MB，按写向 5–28 KB/s → **约 15–45 分钟 attended**。这不是"快闪 fw.dat"，是长传输。⚠️ **能否只推 `fw.dat`（复用旧 Res）把它降到 ~1.6MB/2min，是首个真机实验**（见 §9 O-1）。

**App 完全忽略 `FileList.dat` 与 `FormatExtFlash`**：前者是有线刷写器的清单（设备**从不经 BLE 读它**，`CMakeLists.txt:1526-1548` 构建时生成）；后者是 0 字节哨兵（叫刷写器"先格式化外部 Flash"，固件里**没有任何处理分支**，两个 BLE zip 都不含它）。App 直接把 `ota_all`/`ota_part` 里的文件逐个推即可。

### 1.2 承载通道与可观测性（重要）

- **承载 = AMDTP GATT**（Ambiq Data Transfer Protocol Server，`amdtps_main.c:377`）——就是现网 B2A 那条 `0xFFE0` 通道，OTA 双向都跑在它上面，**不是独立 amota 服务**。
- **App 只能观测到 B2A Ack 包**：REQ/START/DATA/STOP/OFFSET 的结果码（`EBEC_SUCC` 等）+ OFFSET 返回的断点 offset。
- **`OTAR_*` 是手表内部枚举，不发给 App**（只驱动表盘进度弧，`gui_ota_req.c`）。所以 **"整包成功/已生效" App 看不到**——判据只能是：末个 STOP 收 `EBEC_SUCC` → 设备自动切区+重启 → App 重连 → **读版本号/采集能力确认**。
- **没有现成 BLE 上位机参考代码**（两仓都只有设备接收端；`amota_main.c` 是设备端 server，`master_ota_spi.c` 是手表刷板内 modem）。**App 推包序列 = 从 `B2A_FileTrans*Ack` 接收端反推 + 真机抓包验证。**

### 1.3 安全边界（砖化风险已量化收窄）

- **BLE OTA 收流派发只认 `OTA(1)` / `WATCH_BG(3)` / `BP_FW(6)` 三种 ModuleId**；`SECBL_FW(5)`/`BOOTLOADER` 只是枚举、**无 handler**（`ble2appWrap.c` 全部落盘分支 `:3804/4772/5448`）。SecBL 只在**开机**由本地 `OTA_CheckSecBL→OTA_UpdateSecBL` 更新（`ui.c:1128` 开机自检），与 BLE 收流解耦。
- **BLE 包物理上不含** `SecBL.dat`/`boot.bin`/`ble_fw.dat`/`.bin`（只进整包 zip）。
- **有线兜底**：`flash_fw.ps1` J-Link SWD 把 `apollo_eiot_watch.bin` 烧到 `0x18000`（走 SBL flash-helper `0x08000061`），独立于运行中的 app，**可覆盖任意 ≥0x18000 应用/资源区变砖表**。BLE 造成的砖必在此区间 → **无条件可救**（除非 SBL/SWD 口本身坏，而这不可能由 BLE OTA 造成）。
- **固件无签名、无低电门控**（`EBEC_LOW_BAT` 定义了从不返回）→ **安全/防掉电 100% 由 App 兜底**：发起前强制充电+电量阈值。

---

## 2. 协议：FILE_TRANS（Cmd `0x0F`）逐字节

信封 = B2A 8B 帧头 + 4B 命令头（`Cmd=0x0F`），全小端。子命令（`ble2appEx.h:453-458`）：`START=0x01 / TRANS=0x02 / STOP=0x03 / REQ=0x04 / OFFSET=0x06`。

**会话时序（App 驱动，设备不校验顺序、无内置清单）：**

```
REQ(0x04) 报会话总量 ──(设备查磁盘/BUSY → 等设备端 MMI 授权 → 异步回 12B)──▶ READY
  每文件按 App 决定的顺序:
    START(0x01, 带文件名) ─▶ [切片循环: TRANS(0x02) 背靠背发 ≤17 个无响应写
                              → 等 9B DATA ack(含累加和) → App 核对 → 推进 offset]
                            ─▶ STOP(0x03, 无 payload) → 设备 idx++
  末文件 STOP 且 idx==fileCount ─▶ 整场完成 → 设备自动切资源区 + 重启生效
```

**逐字节 payload（`ble2appWrap.c` 反推）：**

| 子命令 | 方向 | payload |
|---|---|---|
| `REQ 0x04` | App→表 | `[0]moduleId=1 [1]ucIsOffset [2]fileCount [3]rsv [4-7]totalSize(LE)`；**fileCount/totalSize = 整场合计**（`:3685-3695`）。设备查磁盘(`totalSize/1024` vs 剩余 KB)，**OK 路等 MMI 授权后异步回**：设计反推为 12B `status/moduleId/fileCount/currFileIdx/sliceMaxSize(LE)/offset(LE)`，**但 golden 日志(2026-07-08)设备侧只见 8B 回显、未见 12B TX（真实格式待抓包，见 implementation-notes BUG-2）**；`sliceMaxSize=(协商MTU−15)×17`（golden 坐实 `ParamPktLen:232=247−15`，含 3B ATT 写头；旧文档 MTU−12 已更正）。 |
| `START 0x01` | App→表 | `[0-3]fileSize [4-7]offset [8-11]sliceSize [12]ucType(BFTT) [13]zipFlag [14-15]nameLen [16..]name`（`:4169-4193`）。**文件去向由文件名字符串决定**（设备 `sprintf(MAC_EXT_ROOT+name)` 裸写），`ucType` 只驱动进度 UI、`zipFlag` 未消费。名字 ≤12 字符（`szFile[13]`）。 |
| `TRANS 0x02` | App→表 | 裸文件数据，切片内多包走 `IS_MULTI_PKT`；**每切片回 9B DATA ack** `[0-3]recvLen [4-7]累加和(U32) [8]EHST_SUCC`（`:4753-5010`）。 |
| `STOP 0x03` | App→表 | 无 payload。设备 `idx++`，按 `ulCurrFileSize` 做落盘尺寸兜底（`:5467`），按文件名特判 `fCheck.dat`/`ResCheck.dat`。 |
| `OFFSET 0x06` | App→表 | 应答 4B `offset`（断点续传点，`:5644`）。 |

**校验归属（三套独立，别混）：**
1. **传输层 9B 累加和**：设备逐字节累加刚收的切片、回给 App（`:4753-4756`）。**App 自己也算期望值、比对，不符则重发该切片**。App 唯一"要算"的校验。
2. **`ResCheck.dat`/`fCheck.dat`**：构建工具预生成、随包下发的**普通文件**，App 只**原样透传落盘**、不构造。设备升级后自己拿它们做 per-file 自检（`OTA_DoRecvAfter`）。
3. **整场成功门**：设备侧 `OTA_DoRecvAfter()==EECT_OK`（新区 Res 三件套存在 + 字库齐）→ gate `OTA_SetFlag(MAC_OTA_FLAG)`+重启。**App 观测不到这一步。**

**两套复位定时器（真机构建已核）：**
- **UI 看门狗 ≈ 15.36s**（`MAX_OTA_TIMTOUT=1024ms × 15`，`gui_ota_req.c:88-122`）——**这是 App 的硬约束**：每 <15s 必须有一个切片完成（触发进度上报清零看门狗），否则设备自复位。
- 后端 B2ATimer ≈ 61.44s（`ble2appWrap.c:3605`，`60*1024 ms`）。
- 结论：**按 ~15s 无进度红线设计**，前台亮屏+稳定包速。

---

## 3. 数据模型（Data Model）

```
OtaPackage
  moduleId = OTA(1)
  files: List<OtaFile>        // 顺序由 App 定; 末文件 STOP 才算整包完成
OtaFile
  name: String               // ≤12 字符, 决定设备落地位置(如 "fw.dat"/"ResData.dat")
  fileType: Int              // BFTT: fw→2 / Res→3 / 字库→7 (仅驱动进度 UI)
  bytes: ByteArray
  sliceSize: Int             // App 自报, 固件校验 ≤ sliceMaxSize
OtaReqReply                  // REQ 的 12B 异步应答(等设备端授权)
  status, moduleId, fileCount, currFileIdx, sliceMaxSize, offset
OtaProgress { fileIdx, fileCount, sentBytes, totalBytes, rateBps }
OtaResult   { DONE_DOWNLOAD | VERIFIED(version) | Failed(reason) }
```

采集包来源：直接嵌入/SAF 选 `采集 ota_all.zip`，App 解压后按 §2 顺序逐文件推（zip 只是分发容器，设备收的是逐个 `.dat`）。

## 4. 接口（Interfaces）

- **`OtaTransport`（shared/commonMain）**：`suspend fun provision(pkg, onProgress): OtaResult`——独占长事务，**仿 `pullLog` 自建 Channel + 空闲超时，绝不进 3s 单飞 `request()`**（TRANS 只写不回）。
- **`BleClient` 扩展**：① 暴露 `negotiatedMtu`（现协商完丢弃）；② 写路径加背压（查 `writeCharacteristic` 返回值 + `onCharacteristicWrite` 完成，现在无响应写即发即返、上千片静默丢）。
- **S7 编解码（新）**：FILE_TRANS 五子命令 encode + **下行多包分片器**（首片带命令头/续片 index 递增/末片 `MULTI_PKT_END`/多包 ID bit[5:4]/`OTA_PART` 位）——全新"App→表"发送方向。
- **`S7MockWatch` 扩展**：fileTrans 状态机 + 逐字节累加和 + **异步 MMI 授权模拟** + 断点续传 + 校验失败注入 + 参数化时延（现在对 `0x0F` 回 NOT_SUPPORT）。

## 5. 状态机（App 侧，由 Ack 驱动）

```
IDLE ─REQ─▶ REQ_SENT ─(等异步授权 12B, 无固定超时; 仅 disk_full/busy 即时错)─▶ READY
READY ─每文件(App 定序)─▶ START ─▶ SENDING[切片: 发≤17 无响应写 → 等 9B ack → 核累加和 → 推 offset]
                                    ─切片完─▶ 下一切片 ─全完─▶ STOP ─idx++─▶ (下一文件 / 末文件)
末文件 STOP(SUCC) ─▶ DONE_DOWNLOAD ─(设备自复位→重连)─▶ 读版本 ─▶ VERIFIED / FAILED
```

纪律：每切片 **< ~15s**（UI 看门狗）；REQ 前电量/充电门控；断连走 OFFSET 查 + `ucIsOffset` 续传（attended 下 best-effort，续传起点**只信设备返回 offset**，别从 0 重发→会被 `ab+` 追加成超长文件强制整包重传）；**STOP 丢 ack 别裸重发**（会二次推进文件索引→缺文件）。

## 6. 用户可见行为

- 设备控制台加 **DEBUG/工具屏「刷入采集固件」**（不进普通用户路径）。
- **发起前门控**：必须充电 + 电量≥阈值 + 亮屏；弹「全程盯着、别锁屏切后台，约 N 分钟」。
- **过程**：文件 x/N、%、速率；到 REQ 授权那步提示「**请在手表上确认升级**」。
- **完成**：「等待手表重启…」→ 自动重连 → 读版本号 → 成功/失败（失败=整包重来，attended 可接受）。

## 7. 风险点（真实包坐实后更新）

| 风险 | 现状/缓解 |
|---|---|
| 变砖 | **已大幅收窄**：BLE 永不碰 SecBL/boot；`flash_fw.ps1` 有线无条件恢复。仍**强制充电门控**防生效阶段掉电。 |
| ~15.36s 看门狗 | 前台亮屏 + 稳定包速，每切片 <15s；无 WAKE_LOCK 先靠 attended 亮屏兜。 |
| 累加和非 CRC、无签名 | App 逐字节复刻累加和；来源可信靠受控环境（实验室）。 |
| 真机字节序未验 | 首台表**抓包核对** FILE_TRANS 各帧再批量（全仓仅 1 条 golden，预留返工窗口）。 |
| 大传输/耗时 | ~19.7MB / 15–45min attended；先验 O-1（能否只推 fw.dat）。 |
| 成功不可观测 | 靠"末 STOP SUCC → 重连读版本"判成功，不靠内部事件。 |
| 字库补拷=旧表字库 | 推 ota_part 时采集跑旧表字库（视觉可能有差，功能无碍）；要视觉正确推 ota_all。 |

## 8. 分阶段（Phase 0 已由两轮固件侦察基本完成）

- **Phase 0 · 逆向坐实**——✅ **基本完成**（本文即产出）。剩：MTU 权威链、AMDTP 特征 UUID 上下行划分、B2A 帧头逐字段偏移（真机抓包时一并核）。
- **Phase 1 · 传输地基（Mock 可验）**：下行多包分片器 + FILE_TRANS 编解码 + `BleClient` MTU 暴露/写背压 + `OtaTransport` 独占事务骨架 + `S7MockWatch` fileTrans 状态机 + commonTest（累加和/分片/断点/授权时序/校验失败/看门狗节奏）。
- **Phase 2 · 端到端（首台真机）**：嵌入采集 `ota_all.zip` + attended 工具屏 + 电量门控 + **首台表抓包核对字节序** + 走完整刷入→重启→读版本；**先做 O-1 实验**（只推 fw.dat？）。
- **Phase 3 · 收尾**：批量配置 SOP + explainer / handoff / quiz。

## 9. Open Questions

- **O-1（高价值实验）**：能否**只推 `fw.dat`（复用旧表 Res 三件套）**让采集固件跑起来？若行，OTA 从 ~19.7MB/30min 降到 ~1.6MB/2min。采集 Res 与 stock 不同（`ResData` 17.8→18.1MB），但采集 fw 可能兼容旧 Res（视觉差异可接受）。**首台真机第一个试。**
- **O-2**：`ucZipFlag` 固件未消费（疑预留）——确认压缩传输不支持，App 一律推明文整文件。
- **O-3**：REQ 的 MMI 授权在真机上是弹窗还是自动？授权耗时窗口？用户拒绝分支？
- **O-4**：AMDTP 特征 UUID 与 notify/write 划分（真机抓包区分上下行需要）。
- **O-5**：采集 `fw.dat` 交付分支（`dev-en-collect` 宏 vs `collect_alg`）与其 BLE/OTA 回归——需在采集表上验"能 OTA 回常规固件"（虽有 J-Link 兜底，反向 BLE 通道健康度仍值得确认）。

---

## 附：溯源

- 固件真源：`E:\1\apollo4_watch_s7`（现网）/ `E:\1\apollo4_watch_s7_collect`（采集）。关键 file:line 见正文。
- 现网 B2A 协议：[`../S7B2A/S7协议共识规格.md`](../S7B2A/S7协议共识规格.md)、[`../S7B2A/protocol-b2a.md`](../S7B2A/protocol-b2a.md) §9。
- 侦察过程与逐条实证：见 [`implementation-notes.md`](implementation-notes.md)。
