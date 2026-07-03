## [6] MEDIUM shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7Console.kt:112
超时后的迟到应答可被下一个同 Cmd/Key 请求错配：drainStale 只在写前执行，覆盖不到写后到达的残留应答
细节: 机理：单飞队列以 (cmd,key) 配对应答，协议无事务 ID。drainStale() 在取得 mutex、发送 TX 之前清一次通道（L105）；若上一个同 Cmd/Key 请求超时（3s）后设备的迟到应答在『本次 drain 之后、本次真应答之前』到达，L112 的 `msg.cmd==cmd && msg.key==key` 会把它当成本次应答返回，本次真应答则滞留通道成为新残留（下次 drain 才清掉，日志记 drain stale）。触发时序（真机形态，该层即为协议冻结后的常驻可靠性层）：① SET KEY_DATE_TIME 发出，设备忙，3.2s 才回 CommAck(0x07 BUSY)；② 3.0s 处 request 抛 Timeout，UI 显示 TIMEOUT；③ 用户 3.1s 重按「同步时间」→ drainStale 时通道为空 → TX 第二个 SET；④ 3.2s 旧 BUSY ack 到达 → 被当作第二次 SET 的应答 → requestAck 抛 DeviceError(BUSY)，而设备实际已成功执行第二次 SET（或反之：旧的 OK ack 掩盖新 SET 的失败）。GET 类则返回一拍旧数据 + 通道残留级联。Mock 下另有触发路径：见 attach/detach 泄漏项⑤（旧 console 的应答进入新 console）。缓
修复: 最小修复：S7Console 增加污染记录字段（如 var poisoned: Triple&lt;Int,Int,Long&gt;?，存超时的 cmd/key + 截止时间 now+requestTimeoutMs）；request 超时抛异常前写入。下次 request 在 L112 匹配循环中，若 msg 与 poisoned 同键且未过截止时间，先丢弃这一帧（log("drop poisoned rx")）并清空 poisoned 再继续等待真应答。改动约 10 行，仅限 S7Console.kt。

## [7] LOW app/src/main/java/io/bluetrace/viewmodel/DeviceConsoleViewModel.kt:118
attach 无视链路状态立即 refreshAll：registry.add 先于 connect 完成，首个请求必然被丢弃吃满 3s 超时；RECONNECTING 时打开控制台最长空转 21s
细节: 机理：attach() 结尾无条件 refreshAll()，而 registry 变化不等于链路就绪。DeviceScanViewModel.toggleConnect（L137-138）先 `registry.add(device)` 再 `bleClient.connect(device)`（Mock connect 需 600ms）；console VM 因 Tab saveState 跨页存活，registry.add 即触发 attach → refreshAll → getDeviceInfo 的 write 在 link=CONNECTING 时被 BleClient 契约静默丢弃 → 空等 3s 超时。触发时序 A：① 打开设置→设备维护（无设备，NotConnected）；② 切采集 Tab → 设备连接，点 S7 连接；③ attach 立即发 getDeviceInfo → 丢弃 → 3s 超时（firstError=TIMEOUT），其余 6 步在 CONNECTED 后成功；④ 切回控制台：FW/Modem/SecBL/BP 显示「—」+ 红条 TIMEOUT，须手动再刷新。触发时序 B：手表处于 RECONNECTING（蓝牙关/出范围）且仍在 registry 中时创建 VM → refreshAll 七步串行各吃 3s = 21s busy、7 次
修复: attach() 中把末尾 refreshAll() 改为在 attachJob 内 launch { withTimeoutOrNull(5_000) { ble.linkState(device.id).first { it == LinkState.CONNECTED } } ?: return@launch; refreshAll() }；更优做法是 collect linkState，在每次转入 CONNECTED 且 info==null 时触发 refreshAll（顺带覆盖重连后自动补刷）。

## [8] LOW shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7Console.kt:83
decoder 仅在 stop() 时 reset，同一 attach 内的断链重连无人调用 reset——违反 S7FrameDecoder「重连须 reset」契约
细节: 机理：S7FrameDecoder 文档明确『每设备每连接一个实例；重连须 reset』（S7Frame.kt L121），但 reset 只有 stop() 这一处调用；VM 仅在 registry 中设备身份变化时才 stop/start console，而 RECONNECTING→CONNECTED 的同设备重连（injectDisconnect 3s 自动回连、setBluetoothOff 关→开恢复）不改变 registry，collectJob 与 decoder 原样存续，多包重组态（pendingCmd/pendingKey/pendingId/pendingParts）跨连接残留。触发时序（真机多包场景；当前 Mock 只发单帧故暂不可达，但该解码器即真机常驻路径）：① 设备回传多包应答，首片(index=0, id=2)已入 pendingParts；② 链路掉线，末片未到；③ 重连后设备发送新的多包消息，其首片因 CRC 错或 s7Inbound DROP_OLDEST 溢出丢失；④ 其续片 id 恰为 2（id 仅 2bit，1/4 概率）→ L195 校验通过，被拼进断链前的 pendingParts；⑤ 末片到达 → 产出 cmd/key 来自旧消息、param 为跨连接拼接的脏消息；若该 cmd/key 恰与在飞请求相同则作为应答返回（各 pars
修复: S7Console.start() 的 collectJob 同 scope 内增开一个 ble.linkState(deviceId) 收集协程：维护 prev 状态，检测到 非CONNECTED→CONNECTED 转变时调用 decoder.reset() 并清空 responses（复用 drainStale 逻辑），stop() 时随 collectJob 一并取消。

## [9] MEDIUM app/src/main/java/io/bluetrace/viewmodel/DeviceConsoleViewModel.kt:216
sendPower 缺链路前置检查与异常处理：已断链时误报「命令生效」，真实端异常则 Waiting 对话框永久卡死/崩溃
细节: sendPower 只查 busy，不查 link。场景 A（Mock 即可触发）：确认对话框打开期间链路转 RECONNECTING/DISCONNECTED（如系统蓝牙关闭走 MockBleClient.setBluetoothOff，或设备自行掉电），用户点「确认执行」→ ble.write 按契约静默丢弃（MockBleClient.write L141 直接 return），但 sendPowerCommand 里 linkState.first{DISCONNECTED||RECONNECTING} 立即匹配 → ok=true → 弹「设备已断开 · 命令生效」——实际上命令根本没发出去，恢复出厂/关机被误报成功。场景 B（换真实 BleClient 后）：`val ok = try { c.sendPowerCommand(key) } finally { busy=null }` 无 catch，write 抛 GATT 异常时 ok 永不赋值、danger 停在 DangerState.Waiting（该 AlertDialog onDismissRequest={} 且无按钮，不可关闭），同时异常沿 viewModelScope 未处理直接崩溃。op() 壳有 catch 而 sendPower 没有，属遗漏。
修复: 两处最小修复：(1) DeviceConsoleViewModel.sendPower 开头加 `if (_state.value.link != LinkState.CONNECTED) { _state.update { it.copy(danger = DangerState.Done(false)) }; return }`（或直接落 error）；(2) 把 `val ok = try { c.sendPowerCommand(key) } finally {...}` 改为 try/catch(Exception)/finally，异常时 ok=false 并落 error，保证 danger 必达 Done。更彻底可在 S7Console.sendPowerCommand 里 write 前检查 `ble.linkState(deviceId).value == CONNECTED`，否则直接返回 false，把「已断链≠命令生效」的判据收敛到协议层。

## [10] MEDIUM app/src/main/java/io/bluetrace/viewmodel/DeviceConsoleViewModel.kt:118
attach 无条件 refreshAll：链路尚在 CONNECTING 时触发，串行烧 3s×N 超时并弹虚假 TIMEOUT
细节: 触发链：DeviceScanViewModel.toggleConnect 先 registry.add(device)（L137）再 suspend bleClient.connect（Mock 600ms 才 CONNECTED）；而 navigateTab 用 popUpTo(saveState)+restoreState，设置栈里留着的控制台页 ViewModel 跨 tab 存活并持续 collect registry.connected。用户按屏内提示「先到 采集→设备 连接」时，add 一发生 attach() 立刻 refreshAll()，首个 GET 的 write 因 link!=CONNECTED 被 BleClient 契约静默丢弃 → 白等满 3s 超时，UI 弹「命令失败：TIMEOUT」且 Model/FW 等字段留空；真实端 connect 耗时更长时最多 7 步×3s=21s 全程 busy（所有按钮禁用），需手动再刷新才恢复。refreshAll 前应等待/校验 link==CONNECTED（或在 link 转 CONNECTED 时再刷）。
修复: attach() 中把直接 refreshAll() 改为在 attachJob 内等链路就绪再刷：launch { ble.linkState(device.id).first { it == LinkState.CONNECTED }; refreshAll() }（可加超时兜底）；或在已有 linkState collector 中检测转入 CONNECTED 且尚未刷新过时触发 refreshAll()。

## [11] MEDIUM app/src/main/java/io/bluetrace/viewmodel/DeviceConsoleViewModel.kt:206
saveLog 在主线程阻塞写盘且 IOException 未捕获：拉日志落盘失败直接崩溃
细节: pullLog 经 op() 跑在 viewModelScope（Main.immediate），saveLog 的 mkdirs()+File.writeBytes() 是主线程阻塞 I/O（设备日志可达数十上百 KB，主线程卡顿）；且 op() 只 catch S7CommandException——外部存储满/卷不可用时 writeBytes 抛 IOException（mkdirs 失败后为 FileNotFoundException），沿 viewModelScope 未处理异常直接杀进程。AppModule 的 getExternalFilesDir(null) ?: filesDir 空回退没问题，但写入失败路径无兜底，也未走 console_err_fmt 呈现。应 withContext(Dispatchers.IO) + try/catch 落到 ui.error。
修复: 在 pullLog 中改为 val saved = if (bytes.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { saveLog(bytes) }.getOrNull() } else null，失败时 _state.update { it.copy(error = "SAVE_FAILED") }（走既有 console_err_fmt 呈现）；或在 saveLog 内 withContext(Dispatchers.IO) + try/catch(IOException) 返回 null 并落 ui.error。

## [12] LOW app/src/main/java/io/bluetrace/ui/screen/settings/DeviceConsoleScreen.kt:326
操作日志时间戳按 UTC 渲染（offset 传 0），非零时区下每行时间偏差整个时区量
细节: OpLogSection 用 `epochMsToLocalParts(line.timeMs, 0).timeCompact()` 硬编码 UTC 偏移 0；同一 VM 的 saveLog 及全工程其他调用点都传 zone.offsetSeconds()。东八区实机上操作日志每行时间显示比本地慢 8 小时——这个控制台恰是排「设备时间/对时偏差」问题用的，UTC 时戳会直接误导比对（比如把 TX/RX 时刻和「设备时间」栏的本地时间对照时对不上）。需要把时区偏移从 VM 暴露给屏（或 VM 生成显示串）。
修复: DeviceConsoleViewModel 已注入 zone: TimeZoneProvider，最小改法：在 VM 加 fun zoneOffsetSeconds(): Int = zone.offsetSeconds()（或直接在 VM 收集 opLog 时把格式化好的显示串存入 opLines），DeviceConsoleScreen.kt:326 改为 epochMsToLocalParts(line.timeMs, vm.zoneOffsetSeconds()).timeCompact()。

## [13] LOW app/src/main/java/io/bluetrace/viewmodel/DeviceConsoleViewModel.kt:177
console_person_none 两语言都定义却无任何引用：未选用户时 UI 显示原始码「命令失败：NO_SUBJECT」
细节: writeCurrentSubject 无当前采集用户时 error="NO_SUBJECT"，ErrorBar 统一套 console_err_fmt 渲染成「命令失败：NO_SUBJECT」（英文 "Command failed: NO_SUBJECT"）。为此场景专门加的本地化引导文案 console_person_none（values/strings.xml:304、values-en:297「未选择采集用户，先在采集页选择」）没有任何调用点，VM 注释声称的「UI 提示去采集页选」实际未接线；且该场景不是命令失败（没发任何命令），套 err_fmt 语义也不对。
修复: 在 DeviceConsoleScreen.kt:74 的 ErrorBar 调用（或 ErrorBar 内部）特判 code == "NO_SUBJECT"：直接显示 stringResource(R.string.console_person_none)，不套 console_err_fmt；其余错误码保持原格式。

## [14] LOW shared/src/commonMain/kotlin/io/bluetrace/shared/ble/MockBleClient.kt:55
S7 手表以 DeviceKind.DUT 进 roster 且无处过滤 PROFILE_S7：会被当采集 DUT 纳入会话，占限额并只产 unparseable 告警
细节: 全库仅设备控制台 attach 和 Mock 内部路由检查 PROFILE_S7，采集链路（DeviceConnectScreen 连接、ConnectionRegistry DUT≤3 限额、CollectViewModels L114 组 AssignedDevice、DefaultSessionController 订阅）都不排除它。按控制台屏内提示连上 S7 后：它占掉 3 个 DUT 名额之一；若此时开始采集，会话会订阅它的 notifications——现在返回的是 B2A 帧流（30s 心跳+命令应答），MockPacketCodec.decode 全部返回 null → DefaultSessionController L186 每帧记一条 WARN "unparseable packet"（写进应用日志），该设备样本 CSV 恒空但仍进 manifest。不崩溃，但产出脏会话且污染诊断日志；应在采集设备选择/会话装配处排除 PROFILE_S7（或给独立 DeviceKind）。
修复: 最小修复：在会话装配处排除 B2A 设备——CollectHomeViewModel 里对 registry.connected 过滤 B2aDetect.matchesAdvertisement（或 profileId == PROFILE_S7）后再算 canStart/connectedDevices 与 startSession 的 devices（CollectViewModels.kt:114）；同时 ConnectionRegistry.dutCount/canConnect 对该类设备不计入 DUT 限额（或给 S7 独立 DeviceKind.MAINTENANCE），并补一条单测：连上 S7 后 startSession 的 config.devices 不含它。
