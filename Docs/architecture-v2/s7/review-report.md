# S7 控制台 · 多代理评审收口记录（2026-07-02）

> 评审方式：3 维度并行审查（协议一致性 / 并发与生命周期 / 集成质量）→ 每个 finding 独立对抗证伪（19 代理）。
> 结果：**15 项确认（2 高危 / 6 中 / 7 低），1 项证伪；15 项全部修复**，修复后 36 例单测全绿 + 实机烟囱测试通过。
> 完整 finding 原文：`_raw/review-findings-6-14.md` + 工作流输出。

## 确认项与修复对照

| # | 严重度 | 位置 | 缺陷 | 修复 |
|---|--------|------|------|------|
| 0 | **HIGH** | S7Console.driftSeconds | 线上 month=0/>12 时 `cum[month-1]` 越界崩 App（RTC 未初始化/恢复出厂后可触发） | `S7DateTime.parse` 增加字段值域校验（month 1-12/day 1-31/h/m/s），非法返回 null → 上层转 DeviceError |
| 1 | MEDIUM | S7Frame 多包重组 | 未校验 uiIndex 连续性：中片被 CRC 剔除后静默拼出带洞"成功"消息 | `pendingNextIndex` 连续性校验 + 首片声明 paramLen 与重组总长比对，不符整片丢弃 |
| 2 | LOW | S7Frame 单帧路径 | 单帧到达即 reset() 毁掉进行中的多包重组（心跳插帧协议合法） | 删除单帧路径 reset()；防泄漏由首片覆盖 + ID/index 校验保证 |
| 3 | LOW | S7Console.pullLog | 设备回错误 CommAck 被忽略，错误/无响应均呈现「空日志成功」 | route() 检测 0x07/0x07 错误 ack → 以异常 close(logSink)，pullLog 抛 DeviceError |
| 4 | **HIGH** | S7Console.sendPowerCommand | TOCTOU：发送前已断链 → write 被丢弃但 first{} 命中初值 → 不可逆命令假成功 | 进锁后前置校验 link==CONNECTED（拒发）；成功判据改为 write 之后的真实状态转变 |
| 5 | MEDIUM | DeviceConsoleViewModel.attach | 在飞 op/sendPower 不随 attach 取消：旧协程污染重置后状态、旧日志误落盘、busy 被提前清零 | 引入随 attach 重建的 `opsScope`（SupervisorJob 挂 viewModelScope）；op finally 校验 console 引用 |
| 6 | MEDIUM | S7Console.request | 超时后的迟到应答错配下一个同 Cmd/Key 请求 | `poisoned` 污染记录（cmd/key/截止时刻），下次请求丢弃截止前到达的首个同键应答 |
| 7/10 | MEDIUM | VM attach 时序 | registry.add 先于 connect：CONNECTING 时立即 refreshAll 白吃 3s×N 超时 | 改为 linkState 首次转入 CONNECTED 才触发 refreshAll |
| 8 | LOW | S7Console 解码器生命周期 | 同 attach 内断链重连无人调 decoder.reset()（违反契约，跨连接可拼脏消息） | start() 增加链路守卫协程：非 CONNECTED→CONNECTED 转变时 reset + drainStale |
| 9 | MEDIUM | VM.sendPower | 无链路前置检查 + 无 catch：真实端异常时 Waiting 对话框永久卡死/崩溃 | 前置 link 检查（Done(false)）+ try/catch(Exception) 保证 danger 必达 Done |
| 11 | MEDIUM | VM.saveLog | 主线程阻塞写盘且 IOException 未捕获 → 存储满直接崩溃 | `withContext(Dispatchers.IO)` + catch，失败报 `SAVE_FAILED` 走 ErrorBar |
| 12 | LOW | DeviceConsoleScreen 操作日志 | 时间戳按 UTC 渲染（offset 硬编码 0），东八区慢 8 小时误导对时排障 | VM 暴露 `zoneOffsetSeconds()`，屏用本地偏移渲染（实机已验证与状态栏一致） |
| 13 | LOW | 未选用户提示 | `console_person_none` 文案无引用，UI 显示原始码「命令失败：NO_SUBJECT」 | ErrorBar 特判 NO_SUBJECT → 显示引导文案 |
| 14 | LOW | 采集链路 | S7 手表以 DUT 身份被纳入采集会话：占限额 + 刷 unparseable 告警 + 产空 CSV 脏会话 | CollectHomeUiState 装配处 `filterNot(B2aDetect.matchesAdvertisement)`，B2A 维护设备不进采集 |

**证伪 1 项**：日志块通道 trySend/SUSPEND 与 s7Inbound DROP_OLDEST 组合的静默丢块指控——验证代理确认当前容量与节流参数下不可达（保留为真机高吞吐时的观察点）。

## 新增回归测试（锁修复）

- `decode_multiPacket_survivesInterleavedSingleFrame`（#2）
- `decode_multiPacket_indexGap_dropsWhole` / `decode_multiPacket_declaredLenMismatch_dropsWhole`（#1）
- `dateTime_invalidFieldValues_returnNull`（#0）
- `powerCommand_notConnected_returnsFalseImmediately`（#4）

## 遗留观察点（不阻塞）

- 真机高吞吐下 s7Inbound/logSink 缓冲参数需按实测调整（证伪项的边界条件）；
- S7 手表占 DUT 连接限额（ConnectionRegistry 计数未改，仅会话装配排除）——若需独立限额，给 B2A 设备单列 DeviceKind（二期）；
- 迟到应答防错配（#6）是无事务 ID 协议下的启发式，真机联调时观察 `drop poisoned rx` 日志频度。
