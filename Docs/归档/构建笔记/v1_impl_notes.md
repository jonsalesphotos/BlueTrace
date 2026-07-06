# BlueTrace v1 实现笔记（agent 工作记忆 · 防上下文压缩丢失）

唯一依据：`/SPEC.md` + `Docs/prototypes/v4_android.html` + `Docs/architecture/bluetrace_v0.proto`。
第一版 BLE=Mock，真实协议解码留接口。验收清单见 `Docs/agent_build_prompt_v1.md` §三。

## 模块 / 包
- `:shared`（KMP commonMain，namespace `com.example.bluetrace.shared`）
  - `util/` Time（done）
  - `domain/` 实体 + repository 接口（扁平设备/Subject/Mode/CollectType/Session…）
  - `ble/` `BleClient` 接口 + 模型 + `MockBleClient`（commonMain，可 JVM 测）
  - `protocol/` 帧头模型(桩) + `SampleDecoder` 接口 + `MockSampleDecoder` + `MockPacketCodec`（mock 编/解一处）
  - `session/` `SessionController` 接口 + `DefaultSessionController`（真实编排，喂 Mock）+ RunState/RunLog
  - `data/` okio 写入器（RawHexWriter/CsvWriter）+ D-6 布局 + Manifest 模型(kotlinx.serialization) + SessionStore
- `:app`（Android，namespace `com.example.bluetrace`）：Compose UI 逐屏 + ViewModel + Koin + DataStore + MediaStore 导出 + 前台服务 + 权限/蓝牙门控 + BleClient 绑定 MockBleClient。

## 关键口径（钉 V4）
- 设备扁平列表 + 限额：DUT≤3（第3黄、第4禁用）+ 参考心率带≤1（HRS 0x180D）。不是 Map<Role,Device>。Device 按 kind 分。
- 采集运行硬锁定（返回拦截，提示"长按结束退出"）；在场感=前台服务通知；无 app 内 Banner（横切E 全局条按原型有，但 SPEC §5.4/§5.1 取消 → 以 SPEC 为准不做 app 内 Banner）。
- 三态：采集中 / 断联(设备卡内联"重连中"琥珀点，会话继续) / 已停止；存储满=自动结束。坏包等进全局诊断日志(不入会话夹)。
- 暂停=仅停数据框滚动显示，不停接收/落盘。
- 实时流 `[时间戳] HEX` 每条一行·锚定底部·无滚动条。Datas(累计行数)+Time(时长)。
- 标签：Pin 瞬时 + Start/Stop 区间；输入框有文字内嵌 X。
- 时间全 unix；文件名 `<Mode>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>`。
- 长按"结束"2 秒（半透明遮罩、松手取消）→ 落盘 → 结束摘要。
- 导出走 MediaStore → `Download/BlueTrace/`，无需存储权限。
- 进程恢复：服务活→重绑续采；全杀→开口会话自动收尾(stopReason=interrupted + toast)。

## 数据落地 D-6（一会话一文件夹）
`files/sessions/<Mode>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>/`
- `raw/dut.hexlog` `raw/reference.hexlog` —— `<epochMs>: HEX` 行，source of truth
- `csv/ppg_g.csv csv/ppg_ir.csv csv/acc.csv csv/hr.csv` —— 每模块解码 CSV，每行 `epoch_ms,device_ts_us,ch...`
- `csv/ppg_acc.csv` —— 组合包兼容 CSV（汇顶 PPG+ACC）
- `gps.csv` —— GNSS 开启时
- `session_manifest.json` —— 开始即写关键信息（sessionId/startEpochMs/timezone/subject/mode/devices/samplingConfig），结束写 endEpochMs/stopReason/质量小结(reconnectCount/disconnectTotalMs/droppedPackets)。

## Mock 数据管线（接口可换真实实现）
- `BleClient` 只发 `BleNotification(deviceId, receivedAtMs, rawBytes)`（真实 Notify 同形）。
- `SampleDecoder.decode(...)` → samples（v1=`MockSampleDecoder` 解 `MockPacketCodec` 格式；真实=Wire+手写定宽）。
- `MockBleClient`：扫描产假 DUT(BT-DUT-0427/0319/...RSSI 抖动)+1 假 Polar H10(HRS 0x180D,♥跳)；可空态。连接后按节流 emit 批包(ppg_g/ppg_ir/acc 组合 + 参考 HR)。可注入断联事件、模拟存储满。

## CollectType（采集类型 sheet · 开关=该路是否落盘/上传）
PPG_G(ppg_g 绿) · PPG_IR(ppg_ir 绿) · ACC(acc 蓝) · GYRO(gyro 蓝) · MAG(地磁 紫) · TEMP(温度 橙)。默认开：PPG_G/PPG_IR/ACC。

## 设计 tokens（原型 <style>）
primary #2BAEEA / primary-deep #1C8EC5 / primary-c #DCF1FB / tertiary(参考/用户) #7C5BC9 / tertiary-c #ECE3FF /
error #E5484D / warning #E89F40 / success(在线/已连) #2AAE6D / success-c #D4F4E2 /
传感器 PPG 绿#2AAE6D · ECG 红#E5484D · IMU/ACC 蓝#2BAEEA · 地磁 紫#7C5BC9 · 温度 橙#E89F40 /
bg #F3F5F8 / surface #FFFFFF / surface-1 #F6F7FB / surface-2 #ECEEF3 / surface-3 #E2E5EC /
on-surface #1B1D22 / on-surface-v #4C525C / outline #8B919C / outline-v #D7DAE0 / radius 8/14/20.
字阶：appbar 标题 19/700；卡标题 13.5–14/700-600；副标 11/400 灰；section header 11/700 大写 灰；mono 9–12.5 tabular。

## 屏清单（37）→ Compose 路由
启动A Splash · 启动B 权限门控(首启,无返回,可跳过) · 启动C 缺权限 Sheet · 启动D 按需/永久拒绝→系统设置 · 启动E 蓝牙关 · 启动F 后台省电指南
三 Tab：采集A 主界面(设备/用户/模式入口 + 开始采集) · 数据(会话列表/分组/筛选/搜索/多选/详情/空态/搜索无果) · 设置(环境权限/GNSS/导出位置/存储/应用日志/设备维护占位/关于)
采集子链(隐藏 Tab)：设备A/B/C → 用户A/B/C → 运行A 就绪 → 运行B 采集中 → 运行C 采集类型 Sheet(进入自动弹) → 运行D 长按2s → 结束A 摘要 → 导出A 进度 / 结束B·导出B Toast / 导出C 失败 / 导出D 空间不足
GNSS A/B/C（设置子页）。横切A 暂停重连(内联) / 横切D 进程恢复(toast，无弹层) —— 按 SPEC 精简。

### 采集A 主界面
AppBar(标题"采集",副"多传感器 BLE 数据采集 · 准备会话",右 statusPill"就绪"绿)；三 EntryTile：设备(DUT·多传感器, 右"N 已连接")/用户(右当前别名+体征badge)/采集模式(内联 Wear|Unwear segmented)；CTA 蓝"开始采集"(无设备/无用户禁用)。
### 设备A
AppBar(返回,"设备连接","单击连接·再点断开",右"已连 N"蓝badge)；搜索(名称/MAC,内联X)；RSSI 滑条"仅显示强于 −80 dBm"；设备行：已连DUT(绿波形icon+名+MAC·-52dBm+绿"已连接"pulse)/参考(紫心icon+Polar H10+紫"参考"+♥72+绿已连接)/连接中(3点动画)/未连接(灰)。第4台DUT行禁用 opacity .5 灰"已达上限"。底"停止扫描"outline。60s 自停。无结果→设备C 空态"重新扫描"。
### 用户A/C
A：当前用户高亮(紫"当前"+✓圆勾)+其他列表；点行=即时选中并返回。底"+新建用户"。C：表单 别名/性别(男|女|其他 segmented)/出生年月/身高/体重 + info"仅本地不上传"，顶/底"保存"。空态 用户B。
### 运行B 采集中（核心）
AppBar(标题"数据采集",副"shb · Wear · 00:02:14",statusPill"采集中"绿pulse)；设备卡(绿icon+名+"在线·acc·ppg_g·ppg_ir"+红♥71)；采集类型 chips 行(可点重开 sheet)；数据框(flex填充)：头 Datas: 128402 / Time: 00:02:14；log-list 锚底无滚动条 mono 9px，每行`[02:13.92] 7E 02 ...`，label行绿`▶ LABEL START · "rest baseline"`；标签区：输入(内联X)+Pin按钮+Start/Stop全宽切换；底栏 暂停(outline)+结束(蓝,长按2s)。
### 运行D 长按
半透明遮罩(透出内容)+环形进度2s+"松手取消·按满2秒停止"；松手取消。
### 结束A 摘要
summary卡(大时长 00:13:24 + "shb·Wear·DUT N传感器+参考" + pills 行数/MB/会话数)；会话文件夹构成 tiles(原始HEX/解码CSV/组合CSV/manifest)；底"查看详情"+"导出"。
### 数据 Tab
AppBar("数据",副"采集会话·N个·MB",右"选择")；搜索+三段筛选(全部|Wear|Unwear)；按日期分组；FileCard(mono 文件夹名 + 传感器tags + "MB·时长")。点→详情；长按/选择→多选(底 删除/导出所选 N)。空态/搜索无果。
### 设置 Tab
分组 list：环境与权限检查→设置A；本机 GNSS(switch); 导出位置→设置B；存储占用→设置C；应用日志→设置E；设备维护(DUT 后期 占位)→设置F；关于→设置D。二期项灰显禁用。
