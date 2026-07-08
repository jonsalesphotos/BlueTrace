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
- 修①`sliceMax` 夹到本地 MTU 分帧容量 `(MTU−12)×17`（防本地低报 MTU 时一切片超固件 17 包硬限）。
- 修②切片重传 drain 提到每次发送前（含首发），清跨切片陈旧 TRANS ack。
- 补测：EC-7 设备 sliceMax 被采纳（非本地猜值）/ SliceFailed 重传耗尽 / EC-1 REQ 授权时延分层（10s>cmdAck 仍成功、超授权窗超时）。

## Decisions（已定）

- **D-1 定位方向 B**（用户 2026-07-07）：实验室 attended 配置少量表，非消费级 OTA。砍掉健壮后台/续传/签名/全 ModuleId。
- **D-2 M7 路线 = B2A 主体扩展**（用户 2026-07-07）：不是"自研 vs UWTP 二选一"；在现网 B2A FILE_TRANS 上做采集固件刷写。UWTP 仅思考方向。
- **D-3 推采集 `ota_all.zip`**（默认，视觉正确）；最小必推 = `ota_part` 四文件（Res 三件套 + fw.dat）。采集包已预构建，不自己 build。
- **D-4 成功判据 = 重连读版本号**：App 观测不到设备内部 `OTAR_*`/生效事件，只能看 B2A Ack；末 STOP SUCC → 重启 → 重连读版本确认。
- **D-5 App 忽略 `FileList.dat`/`FormatExtFlash`**：那是有线刷写器的东西，BLE 不经手，固件无处理分支。
- **D-6 事务层仿 `pullLog` 独占长事务**，绝不进 3s 单飞 `request()`（TRANS 只写不回，会白等超时卡死）。
- **D-7 按 ~15.36s UI 看门狗设计**（非 60s）：每切片 <15s 出一个进度上报。

## Deviations（偏离/意外，含推翻早期结论）

- **DV-1 推翻"采集=换整颗主镜像的高 brick 风险"的悲观口径**：BLE OTA 永不碰 SecBL/boot（包排除 + 固件无 handler，`ble2appWrap.c` 落盘分支只认 OTA/WATCH_BG/BP_FW），且 `flash_fw.ps1` 有线无条件恢复 → 风险降到"中 + 可恢复"。
- **DV-2 推翻"stock→采集 = fw.dat 快闪"**：逐文件对比采集与 stock 包，Res 三件套/CN 字库/fw 全不同（仅 fNum* 相同）→ 必推采集 Res+fw，~19.7MB / 15–45min 大传输。
- **DV-3 校验归属澄清**：App 只算/比**传输层 9B 累加和**；`ResCheck.dat`/`fCheck.dat` 是随包透传的普通文件，设备自检用，App 不构造。
- **DV-4 无现成 BLE 上位机参考**：两仓只有设备接收端，推包序列全靠反推 + 真机抓包。`amota_main.c`=设备端 server，非主机端。
- **DV-5 Mock ack 帧口径为约定**（Phase 1）：Mock/session 双方约定应答 = `encodeResponse(0x0F, 子键, param)`（cmd/key 回显）；9B ack 的 `recvLen` 取"本切片长"（非累计）。真机 ack 的 cmd/key 与 recvLen 语义待抓包核（Phase 2）——若真机 recvLen 为累计，则 EC-3/跨切片 ack 错配自然消解。
- **DV-6 sliceMax 夹本地容量**：会话取 `min(设备回值, (MTU−12)×17)`，而非纯信设备值——本地 MTU 低报时纯信设备值会超固件 17 包/切片硬限（审查 medium 修复）。

## Edge Cases（正确性陷阱，实现必须处理）

- **EC-1 REQ OK 路是异步授权**：等设备端 MMI 授权后才回 12B，不能套固定超时判失败（仅 disk_full/busy 即时错）。
- **EC-2 多文件 STOP**：只有末文件 STOP（idx==fileCount）才算整包完成；中间文件 STOP 只 idx++。App 别在中间 STOP 触发完成。
- **EC-3 STOP 非幂等**：STOP 无 payload、靠内部 idx++ 推进；丢 ack 裸重发会二次推进→缺文件。需去重/谨慎重传。
- **EC-4 断点续传写模式由固件按持久化 offset 隐式决定**：App 必须用 REQ/OFFSET 返回的 offset 当续发点；从 0 重发而固件 offset>0 → `ab+` 追加成超长文件 → 被尺寸兜底强制整包重传。
- **EC-5 切片内零背压**：9B ack 是每切片（最多 17 包）才回一次；切片内多包纯只写，别"每包等 ack"（没有），也别"随便连发"（溢出 GATT 缓冲静默丢，`writeCharacteristic` 返回 false 要查）。
- **EC-6 文件名 ≤12 字符**（`szFile[13]`），超长写错名致资源校验找不到。
- **EC-7 分片上限只信设备 REQ 回的 `sliceMaxSize`**（=(协商MTU−12)×17），别用本地 MTU 猜值。

## Open Questions（见设计文档 §9，此处只记状态）

- O-1 只推 fw.dat 复用旧 Res？→ **首台真机第一个实验**（决定 2min vs 30min）。⏳
- O-2 `ucZipFlag` 是否预留（不支持压缩）？⏳
- O-3 REQ MMI 授权真机形态/耗时/拒绝分支？⏳
- O-4 AMDTP 特征 UUID 上下行划分（抓包需要）？⏳
- O-5 采集 fw 分支 + 反向 BLE 回刷通道健康度？⏳
- O-6（Phase 2 硬化）真机 9B ack 的 `recvLen` 是否累计？切片重传遇"真·迟到 ack 落在下一等长等和切片 await 窗口"的残余竞态需据此根治（累计 recvLen 或 ack 带 offset/seq 即可强匹配）；Mock 恒即时回不触发。⏳

## 侦察溯源（两轮固件工作流）

- 盲点侦察（6 路 recon + 3 路批判）：run `wf_9654d3a2-58f`。
- 包语义坐实（4 路）：run `wf_9623e3b4-b04`。
- 真实包检查：`E:\1\apollo4_watch_s7\out\product\apollo_eiot_watch\apollo_eiot_watch_ota_all\`（14 文件）+ `apollo_eiot_watch_ota_all.zip`/`ota_part.zip`；采集包 `apollo4_watch_s7_collect/out/.../`。
