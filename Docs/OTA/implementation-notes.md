# S7 采集 OTA · Implementation Notes（活文档）

> 执行期维护。设计与计划见 [`S7采集OTA_设计.md`](S7采集OTA_设计.md)。
> 记 Decisions / Deviations / Edge Cases / Open Questions；固件事实带 file:line。

## Phase 1 交付（2026-07-07，Mock 全绿）

传输地基落码（shared 121 单测全绿，含 OTA 13 例；app 单测+assembleDebug 绿）：
- `S7Frame.kt` `encodeMultiPacket` 下行多包分片器（不变量：分片→`S7FrameDecoder` 无损重组回同一 `S7Message`）。
- `S7FileTrans.kt` FILE_TRANS 五子命令编解码 + 9B/12B/OFFSET 解析 + U32 累加和。
- `OtaPackage.kt` 数据模型（Package/File/Progress/Result/Failure）。
- `S7OtaSession.kt` 独占长事务（REQ→逐文件 START/切片/STOP→末 STOP 即 DoneDownload；切片 ack 背压+累加和核对+重传）。
- `BleClient.negotiatedMtu`（接口+Mock+真机 `AndroidBleClient.onMtuChanged` 存值）。
- `S7MockWatch` fileTrans 状态机 + 注入旋钮（REQ 拒绝/坏校验和/按 offset 恒 NAK/切片长记录）。

**三视角对抗审查（workflow `wu7jk1vvl`，24 agent）→ 2 真 bug 已修 + 3 测试缺口已补**：
- 修①`sliceMax` 夹到本地 MTU 分帧容量 `(MTU−12)×17`（防本地低报 MTU 时一切片超固件 17 包硬限）。**⚠️ 该常数 MTU−12 后经 golden 日志更正为 MTU−15**（见下「Golden 日志验证」BUG-1；夹取逻辑本身正确、只是基准常数偏 3B）。
- 修②切片重传 drain 提到每次发送前（含首发），清跨切片陈旧 TRANS ack。
- 补测：EC-7 设备 sliceMax 被采纳（非本地猜值）/ SliceFailed 重传耗尽 / EC-1 REQ 授权时延分层（10s>cmdAck 仍成功、超授权窗超时）。

## Golden 日志验证（2026-07-08，首个真机 OTA 报文）

真源：`E:\1\apollo4_watch_s7\Docs\log\ota.log`（设备侧串口，2026-07-08 16:05:24→16:15:11，**一次完整成功的 ota_all 14 文件刷写**，全程无错误/断点/重传 = happy path）。此前全仓仅 1 条 golden（产测握手），OTA 各帧均为反推——本日志把设计 §2/§5 的字节假设**首次落到真机报文**。

**逐字段坐实（设计假设 → 真机证据）：**
- **MTU=247**（`attSetMtu() mtu:247`）。
- **REQ 时序**：`FILE_TRANS_REQ ucStatus:0x0a` → `B2A_FileTransReqAck() uiLen:8`（8B = 请求回显 `01 00 0e 00 8f 6e 6c 01` = moduleId=1/fileCount=0x000e=14/totalSize=0x016c6e8f=23883407）→ `B2A_FileTransReqAckFromMMI() lAck:1`（**同一 ms 即时授权，无用户等待窗**）→ `FiTransReqFoMMI ParamPktLen:232, SliceMaxSize:3944`。
- **⭐ ParamPktLen:232 = MTU(247) − 15**（8B B2A 头 + 4B 命令头 + **3B ATT 写头**）；SliceMaxSize:3944 = 232×17。**这推翻了"每帧 param = MTU−12"的口径**（见 BUG-1）。
- **START 26B 逐字段全中**：fCheck.dat 帧 `6c000000 00000000 680f0000 02 00 0a00 "fCheck.dat"` = fileSize=108/offset=0/sliceSize=3944/type=2/zip=0/nameLen=10/name — **与 `encodeStart` 布局一字节不差**。
- **14 文件真机顺序**（官方 App）：fCheck→fCN26/34/40→fNum48/64/72/80/96/120→fw.dat→ResCheck→ResData→ResFat；**全部 type=2**（官方 App 不按 BFTT 区分文件类型，FT_RES=3/FT_FONT=7 从未被用；设备也不据此路由）。fw.dat=1,670,216（stock 重刷，验的是传输层，与采集 fw 1,613,656 无关）。
- **DATA ack**：`B2A_FileTransDataAck3 recv=本切片长`（108/3944/末片 857，**非累计**）+ `chk=U32 累加和` → **O-6 解决：recvLen 是本切片长不是累计**，Phase 1 口径正确；跨切片陈旧 ack 因累加和逐片不同天然被拒（仅完全相同字节的切片才会 alias，罕见）。
- **STOP ack uiLen:16**（16B 摘要，疑 MD5）——当前 App 只判 commAckOk 不解析该摘要，attended 可接受（可选增强）。
- **完成路径逐步吻合设计 §2/§5**：[14/14]→`B2A_ResCheck`(ResFat 0xf7fc/ResData 0xab9e 设备自校验 OK)→`OTA_StopTask`→`OTA_DoRecvAfter`(OldPath 0:/A NewPath 0:/B；Step1 Res 三件套 OK；Step2 fCheck+9 字库 OK；END lRet:0)→`OTA_SetFlag(1)`→`OTA_Check(ucOtaFlag:1 ErrCnt:0/30, need OTA goto SecBL 0x001e2000)`→`E2P_LoadRes2Psram`→`SYS_Reset`。**App 观测边界（DV/D-4）证实**：这些全是设备内部，App 只能看末 STOP SUCC。
- **实测速率 ~40KB/s**（22.78MB / 9m47s；ResData 17.8MB 单文件占 ~7min），快于设计 5–28KB/s 估计；15.36s 看门狗全程未触发。

**BUG-1（已证，真机必挂）· 每帧 param 应 = MTU−15 而非 MTU−12：** `AndroidBleClient.onMtuChanged` 存原始 MTU 247，`negotiatedMtu()` 返回 247，`S7FileTrans.maxParamPerFrame(247)=247−12=235` → 每帧 param 235 → 线上 B2A 帧 247B → ATT Write PDU 需 250B > MTU 247 → **Android 写被拒/首切片不达 → DATA ack 超时 → 首片即 SliceFailed 中止**。`defaultSliceMaxSize(247)=3995≠真机 3944`。EC-6 的 `sliceMax=min(设备,localCap)` 只夹对了切片"总尺寸"、夹不住"每帧尺寸"，帧仍超 MTU。**修：预留 3B ATT 头，`maxParamPerFrame = mtu − 15`（或 negotiatedMtu 存 mtu−3 的可用值）。** 波及 `S7FileTrans.kt:39/42`、`S7Frame.kt:105` 注释、`S7MockWatch.kt:48`、`BleClient.kt:69` 注释、`S7OtaSession.kt:81-84`。

**BUG-2（风险，待抓包定档）· REQ 应答真机疑 8B 回显、非 12B：** 设备侧只见 8B `B2A_FileTransReqAck`（请求回显），未见含 sliceMaxSize/offset 的 12B 下行帧（设备日志不 dump TX 帧，无法证否）。若真机应答确为 8B，`parseReqReply` 要求 ≥12B → 返 null → `Malformed("REQ")` → **未发一片即 abort**。且官方 App 送 START 时 slice 已=3944=(247−15)×17，强烈暗示 **App 自算 sliceMax、不依赖设备回值**（设备/ App 各按同一 MTU 算，天然一致）。**建议：sliceMax 一律本地按 (MTU−15)×17 算；REQ 应答宽容解析（8B/12B 都接，只取 status 判 OK/拒），不硬依赖 12B。O-4 抓包时一并定 REQ 应答真实格式。**

**ota2.log 定档（2026-07-08，带 TX 帧）→ BUG-2 翻案 + STOP 澄清：**
- **REQ 应答真机 = 12B**（TX 帧 `bb 00 10 00 5a 66 00 00 0f 04 0c 00 · 00 01 0e 00 68 0f 00 00 00 00 00 00`，paramLen=0x0c=12），逐字段与设计/`parseReqReply` 一致；固件头 `b2a_protocol.h` 明证「Both paths produce the same 12-byte layout」。**BUG-2 是虚惊**——早前 8B `01 00 0e 00…` 是内部 SDM 记录、非上链 TX 帧。防御式改动无害保留（更稳）。
- **STOP 请求 16B 被设备忽略**：固件 `B2A_FileTransStopAck`(`ble2appWrap.c:5400`) 对 pszData **只 LOG_HEXDUMP、不解析任何字节**；完整性靠 ①每切片累加和 ②STOP 磁盘尺寸==START 声明尺寸兜底(`:5467`, 不符回 `EBEC_FSUM_FAIL`) ③末文件 `B2A_ResCheck`。**Phase 1 空 STOP 正确**，官方 App 那 16B(疑 MD5) 是它自己的事。
- **全部帧 TX 坐实**：START ack=1B `EBEC_SUCC`；DATA ack=9B `recvLen(本切片长)+累加和+status`；STOP ack=1B。O-4 承载=复用控制台 B2A/AMDTP 通道(现有 BleClient 已订阅), 无需新 GATT 发现。
- **权威文档成文**：[`S7采集OTA_指令交互与流程.md`](S7采集OTA_指令交互与流程.html)（md+html，7 图，每帧真机 hex + 固件 file:line + 逐字段解码）。

**修复落地（2026-07-08，Phase 1.1，shared:jvmTest + app:assembleDebug 全绿）：**
- BUG-1：`S7FileTrans.maxParamPerFrame = mtu − ATT_HEADER(3) − 8 − 4 = mtu−15`；`defaultSliceMaxSize(247)=3944`。同步改注释于 `S7Frame.kt` / `BleClient.kt` / `S7MockWatch.kt`。新测 `sliceFragmentation_respectsMtuMinus15_perGoldenLog`（断言 232/3944/17 帧/首帧恰 244=MTU−3，旧 MTU−12 下逐条 fail）。
- BUG-2：`S7OtaSession` REQ 应答改**防御式**——`parseReqReply` 返 null（短应答）不再 abort，按"已授权 + 本地 sliceMax"继续；仅 12B 可解析且 status≠OK 才判拒；真·拒绝在随后 START ack 暴露（优雅降级为后段失败，不静默挂起）。`S7MockWatch.otaShortReqReply` 注入 8B 回显 + 新测 `provision_survivesShortReqReply_usesLocalSliceMax`。
- **仍待抓包**：真机 REQ 应答帧真实 cmd/key/长度（决定走 12B 还是防御分支）、AMDTP notify/write UUID（O-4）——见"从机下行 notify 原始字节"抓取项。

## Phase 2 · 下载后回连 + 读版本显示 编排层（2026-07-08，shared:jvmTest + assembleDebug 全绿）

用户选定 Phase 2 首块 = 完成确认闭环（Mock 可验、无需硬件）。**用户 2026-07-08 定：不做版本校验**——OTA 包不含版本信息，无法"包版本 vs 设备版本"机器比对；改为"回连后读取并**显示**当前版本"，是否刷成功由人工看版本判断。落码：
- **`OtaProvisioner.kt`**（新）：`provisionAndReconnect(pkg,onPhase,onProgress)` = Downloading → WaitingReboot(best-effort 等设备断链) → Reconnecting(connect+等 CONNECTED, 重试 N 次) → ReadingVersion(重试) → `OtaResult.Reconnected(currentVersion?)` / `Failed`。下载失败(REQ 拒绝/切片耗尽)直接透传。`readVersion` 外部注入(生产接**重连后新建**的 `S7Console.getDeviceInfo().swVer`)、Mock 可测。**读不到版本不算失败**（Reconnected(null)，UI 显示"未知"）；只有重连失败才 `Failed(ReconnectFailed)`。
- **`OtaResult`**：`DoneDownload`(session 终态) / **`Reconnected(currentVersion: String?)`**(编排终态, 仅展示非判据) / `Failed`。**`OtaFailure` 扩** `ReconnectFailed`（去掉了验证类 `VersionReadFailed`/`VersionMismatch`）；新增 **`OtaPhase`** 枚举驱动 UI 文案。
- **`S7MockWatch.otaRebootAfterComplete`**：末 STOP 后随 ack `disconnectAfter` 断链, 模拟设备自复位。
- **`S7OtaProvisionerTest`** 6 例：重启→重连→读当前版本 Reconnected / 重连恒失败 ReconnectFailed / 版本读不到→Reconnected(null) / 版本读抛异常→Reconnected(null)(守 A 修复) / 不复位仍重连读版本(connectCalls==0) / 下载失败透传。
- **对抗审查（1 agent）修 1 必修**：**A** — `readVersionWithRetry` 原对抛异常的 `readVersion` 零防御，而生产读法 `S7Console.getDeviceInfo()` 超时/失败是 **抛 `S7CommandException`**（`S7Console.kt:155/234`）非返 null → 会异常穿透 + 击穿重试。已修：捕获非取消异常转 null 重试、`CancellationException` 上抛。B（陈旧 CONNECTED 短路，A 修后自愈，测 `connectCalls==0` 钉住）留真机期。C/D/E 无真缺陷。**F（判据太松）随"去掉版本校验"自然消解**——不再有 acceptVersion，无假 Verified。
## Phase 2b · DEBUG「OTA 测试」界面（2026-07-08，shared:jvmTest + assembleDebug 全绿，APK 已装机）

真机 OTA 测试通道落码——选包/校验/UI/循环全就位（用户 zip = stock `apollo_eiot_watch_ota_all.zip`，目标手表 MAC 尾数 D8F7）：
- **`OtaPackageValidator`**（shared，可测，8 测）：合法 zip"简单校验"= 必推核心 `ResData/ResFat/ResCheck/fw.dat` 存在（缺=硬错）+ 名 ≤12 + 无 0 字节 + 缺字库软警告；`sortByPushOrder` = golden 序（字库→fw→Res）。
- **`OtaZipLoader`**（app）：SAF Uri → `ZipInputStream` 解压（zip 内 deflate，自动解）→ `List<OtaFile>`(type=FT_FW 对齐 golden) → 校验 → `OtaPackage`。24MB 全入内存（ResData 17.8MB 单 ByteArray，手机堆够）。
- **`OtaTestViewModel`/`OtaTestScreen`**：目标=registry 首个非参考设备；选包→校验展示→循环次数(默认 1)→循环跑 `OtaProvisioner.provisionAndReconnect`（**遇失败即停**）；进度绑 `OtaPhase`/`OtaProgress`；readVersion=短命 `S7Console.getDeviceInfo().swVer`；每轮 Reconnected 后 `registry.add` 补登记。DEBUG-only（`BuildConfig.DEBUG` 门控，控制台 DangerSection 入口）。
- **`AndroidBleClient.connect` 复位后重连已支持**：断链回调 `conns.remove`+`gatt.close`，再 `connect` 时 link 非 CONNECTED → `getRemoteDevice(MAC)`+connectGatt 建新 GATT（审查 B 点真机侧无需额外处理）。
- **zip 已推手机**：`/sdcard/Download/apollo_eiot_watch_ota_all.zip`（3958891 字节）。
- **待真机（D8F7）**：首台走通 刷入→复位→重连→读版本；O-1（只推 fw.dat）；续传/错误路径；采集包联调。

## Decisions（已定）

- **D-1 定位方向 B**（用户 2026-07-07）：实验室 attended 配置少量表，非消费级 OTA。砍掉健壮后台/续传/签名/全 ModuleId。
- **D-2 M7 路线 = B2A 主体扩展**（用户 2026-07-07）：不是"自研 vs UWTP 二选一"；在现网 B2A FILE_TRANS 上做采集固件刷写。UWTP 仅思考方向。
- **D-3 推采集 `ota_all.zip`**（默认，视觉正确）；最小必推 = `ota_part` 四文件（Res 三件套 + fw.dat）。采集包已预构建，不自己 build。
- **D-4 判据 = 下载完成 + 重连成功；版本仅展示**（用户 2026-07-08 收窄）：App 观测不到设备内部 `OTAR_*`/生效事件；末 STOP SUCC → 重启 → 重连 → **读取显示当前版本**（OTA 包无版本信息，不做机器校验，人工看版本判断）。
- **D-5 App 忽略 `FileList.dat`/`FormatExtFlash`**：那是有线刷写器的东西，BLE 不经手，固件无处理分支。
- **D-6 事务层仿 `pullLog` 独占长事务**，绝不进 3s 单飞 `request()`（TRANS 只写不回，会白等超时卡死）。
- **D-7 按 ~15.36s UI 看门狗设计**（非 60s）：每切片 <15s 出一个进度上报。

## Deviations（偏离/意外，含推翻早期结论）

- **DV-1 推翻"采集=换整颗主镜像的高 brick 风险"的悲观口径**：BLE OTA 永不碰 SecBL/boot（包排除 + 固件无 handler，`ble2appWrap.c` 落盘分支只认 OTA/WATCH_BG/BP_FW），且 `flash_fw.ps1` 有线无条件恢复 → 风险降到"中 + 可恢复"。
- **DV-2 推翻"stock→采集 = fw.dat 快闪"**：逐文件对比采集与 stock 包，Res 三件套/CN 字库/fw 全不同（仅 fNum* 相同）→ 必推采集 Res+fw，~19.7MB / 15–45min 大传输。
- **DV-3 校验归属澄清**：App 只算/比**传输层 9B 累加和**；`ResCheck.dat`/`fCheck.dat` 是随包透传的普通文件，设备自检用，App 不构造。
- **DV-4 无现成 BLE 上位机参考**：两仓只有设备接收端，推包序列全靠反推 + 真机抓包。`amota_main.c`=设备端 server，非主机端。
- **DV-5 Mock ack 帧口径为约定**（Phase 1）：Mock/session 双方约定应答 = `encodeResponse(0x0F, 子键, param)`（cmd/key 回显）；9B ack 的 `recvLen` 取"本切片长"（非累计）。真机 ack 的 cmd/key 与 recvLen 语义待抓包核（Phase 2）——若真机 recvLen 为累计，则 EC-3/跨切片 ack 错配自然消解。
- **DV-6 sliceMax 夹本地容量**：会话取 `min(设备回值, (MTU−15)×17)`，而非纯信设备值——本地 MTU 低报时纯信设备值会超固件 17 包/切片硬限（审查 medium 修复；常数 MTU−12→MTU−15 见 golden 日志 BUG-1）。有回归测试 `provision_clampsDeviceSliceMax_downToLocalCap`（设备回 8000 > localCap 3944 → 钳到 3944）。

## Edge Cases（正确性陷阱，实现必须处理）

- **EC-1 REQ OK 路是异步授权**：等设备端 MMI 授权后才回 12B，不能套固定超时判失败（仅 disk_full/busy 即时错）。
- **EC-2 多文件 STOP**：只有末文件 STOP（idx==fileCount）才算整包完成；中间文件 STOP 只 idx++。App 别在中间 STOP 触发完成。
- **EC-3 STOP 非幂等**：STOP 无 payload、靠内部 idx++ 推进；丢 ack 裸重发会二次推进→缺文件。需去重/谨慎重传。
- **EC-4 断点续传写模式由固件按持久化 offset 隐式决定**：App 必须用 REQ/OFFSET 返回的 offset 当续发点；从 0 重发而固件 offset>0 → `ab+` 追加成超长文件 → 被尺寸兜底强制整包重传。
- **EC-5 切片内零背压 → ✅ 真机坐实并修（2026-07-08，ota3.log，Phase 2c）**：9B ack 是每切片（最多 17 包）才回一次，切片内多包纯只写；但"随便连发"会溢出 GATT 缓冲**静默丢**（`writeCharacteristic` 返 BUSY 被吞）。**真机首刷即中招**：fCheck 单帧过、fCN26 首切片 17 帧全丢 → 设备 15.36s 看门狗 abort。修 = `AndroidBleClient.write` 逐帧串行 + 等 `onCharacteristicWrite` 再发下一帧（No-Response 写亦回调，作节流）+ BUSY 短退避重试 + 机型不回调 300ms 兜底。
- **EC-6 文件名 ≤12 字符**（`szFile[13]`），超长写错名致资源校验找不到。
- **EC-7 分片上限 = min(设备 REQ 回的 `sliceMaxSize`, 本地 (协商MTU−15)×17)**；设备不回值(短应答)时本地作权威（golden 日志：官方 App 自算 = 3944）。

## Open Questions（见设计文档 §9，此处只记状态）

- O-1 只推 fw.dat 复用旧 Res？→ **实测不可行（log5.txt，2026-07-08，先前"可行"判断被推翻）**。固件静态分析(workflow)对了一半：编译版 `OTA_DoRecvBefore`(:197) 确是空壳(`:222` 无条件 return；:788"差分拷 Res"版是死码)、**lData 全量/差分设备侧无效**——但**漏了"目标区不保留 Res"这条**。真机实测：目标区 A **OTA 前有完整 Res**(`APP_OtaReqEntry` flist: RESCHECK/RESDATA/RESFAT 都在)、**OTA 后只剩 fw.dat**(Res+字库全没)→ `DoRecvAfter Step1: Missing 0:/A/ResCheck.dat`→`lRet:1101`(EECT_OTA_LOAD_RES)→ **末 STOP 回 code=13(EBEC_FSUM_FAIL)**→ App 正确显失败(`DeviceError STOP:fw.dat code=13`)。**非砖**(ucOtaFlag:0 不置标志不刷入, 正常复位)。传输本身 ✅ fw.dat 1.67MB **~30s**(流控修复后)。**擦除机制待固件侧定位**(不在空壳 DoRecvBefore/单文件 fremove/:788 死码, 疑接收路径格式化目标区)。**结论**：只推 fw.dat **不够**——设备末只留推送的文件, Res 不保留/不补拷。**可行路径**：① **ota_part**(fw+Res 三件套, 去字库)~8min/稳(Res 被推进目标区, 字库 DoRecvAfter Step2 补拷); ② **真 O-1(~1min) 需固件改**：DoRecvAfter Step1 缺 Res 则从旧活动区 `SYS_CopyFile` 补拷(镜像 Step2 字库补拷)——目标区入口本有 Res, 或"别擦"或"补拷"皆可(用户持固件仓)。校验器 fw-only 仍 valid 但警告已改"实测失败 code=13"。⚠️ workflow Result 1(西语)误报"差分拷 Res/:788 编译"已剔除。**判定：不改固件则用 ota_part；要 ~1min 则改固件。**
- O-2 `ucZipFlag` 是否预留（不支持压缩）？⏳
- O-3 REQ MMI 授权真机形态/耗时/拒绝分支？→ **部分**：本 build/机型 golden 日志里 `lAck:1` 与 REQ 同 ms 即时授权、无弹窗等待；但不排除他机弹窗，`authorizeTimeoutMs` 异步等待保留。拒绝分支仍未见。⏳
- O-4 AMDTP 特征 UUID 上下行划分（抓包需要）？→ 设备串口日志无 UUID，仍须 App/sniffer 抓；与 BUG-2 的 REQ 应答格式一并定。⏳
- O-6 真机 9B ack 的 `recvLen` 是否累计？→ **已解决**：golden 日志 `B2A_FileTransDataAck3 recv=本切片长`（非累计），Phase 1 口径正确。跨切片 alias 仅在"两切片字节完全相同"时才可能（累加和逐片不同 → 通常自动拒），attended 下可接受；如需根治再让 ack 带 offset/seq。✅

## 侦察溯源（两轮固件工作流）

- 盲点侦察（6 路 recon + 3 路批判）：run `wf_9654d3a2-58f`。
- 包语义坐实（4 路）：run `wf_9623e3b4-b04`。
- 真实包检查：`E:\1\apollo4_watch_s7\out\product\apollo_eiot_watch\apollo_eiot_watch_ota_all\`（14 文件）+ `apollo_eiot_watch_ota_all.zip`/`ota_part.zip`；采集包 `apollo4_watch_s7_collect/out/.../`。
