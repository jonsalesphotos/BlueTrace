# S7 设备维护控制台 · 修改记录

> 本文件记录设备维护（DUT）控制台从设计到真机联调再到体验优化的关键改动。
> 完整设计见同目录 [protocol-spec.md](protocol-spec.md) / [plan.md](plan.md) / [command-status.md](command-status.md)。

## 2026-07-03 · 日志大小人类可读 + 后缀改回 .log（第 11 轮）

- **文件大小人类可读**：列表由裸字节 `55122 B` 改为 `53.8 KB`（B / KB / MB / GB，保留 1 位小数，固定用 `.` 作小数点，`ConsoleLogListScreen.humanSize`）。
- **后缀改回 `.log`（去 `.txt`）**：写入 MIME 由 `text/plain` 改为 `application/octet-stream`——MediaStore 对 octet-stream 不强制补扩展名，`s7_devlog_*.log` 保持原名。
- **旧文件尽力改名**：首次进列表把历史 `s7_devlog_*.log.txt` 通过 MediaStore `update(DISPLAY_NAME)` 改名回 `.log`（`renameLegacyTxtToLog`）；非本 app 拥有的旧文件会抛 `RecoverableSecurityException`，`try/catch` 跳过。
- **真机验证**（Redmi / Android 13）：迁移写入的探针文件保持 `.log`；列表 5 份真机日志全部 `.log`、大小显示 `53.8 KB / 487.1 KB / 486.3 KB / 52.0 KB / 43.2 KB`；本机 5 份历史 `.log.txt` 全部成功改名（跨安装亦可）；探针清理后无残留幽灵行。截图 `assets/k_*.png`

## 2026-07-03 · 设备日志改存公共 Download（第 10 轮）

- **反馈**：设备日志列表读的是 app 私有 `Android/data/io.bluetrace/files/devlogs/`，新版安卓文件管理器难进——**能否放到 Download 目录**。
- **改动**：`DeviceLogStore` 由「私有 File 目录」重写为 **MediaStore 后端**，存/列/读一律落到公共 `Download/BlueTrace/logs/`（scoped storage，minSdk 29，**免存储权限**）。
  - `save` → `MediaStore.Downloads` 插入 `RELATIVE_PATH=Download/BlueTrace/logs`、字节直写；`list` → ContentResolver 查询（`s7_devlog` 前缀、按 `DATE_MODIFIED` 降序）；`read` → 按 DISPLAY_NAME 查 id → `openInputStream` 读回。
  - `pullLog` 不变（仍 `logStore.save`），列表/查看页自动改读 Download。
  - **一次性迁移**：首次进列表把遗留私有 `devlogs/` 的日志搬进 Download 后删原件，历史日志不丢。
- **注**：本轮用 `text/plain` MIME，MediaStore 会补 `.txt` 后缀（`s7_devlog_*.log` → `.log.txt`）——第 11 轮改回 `.log`。
- **真机验证**（Redmi / Android 13）：进列表后私有目录清空、`Download/BlueTrace/logs/` 出现刚拉的 `s7_devlog_C8D3E0C1D8F7_*.log.txt`（55122 B）并与早期 4 份真机日志同列；点开显示真实固件日志（`power_on V1.1.99.02` / `BLE FW ver=0x01160000` / `ActivityData…`，1110 行）。截图 `assets/j_*.png`

## 2026-07-03 · 日志列表 + MAC 命名 + 滚动条 + 界面行号（第 9 轮）

针对日志查看的一组细化（本轮）：

| # | 反馈 | 改动 |
|---|------|------|
| 1 | 日志文件夹的所有日志先用列表，手动选择查看哪一条 | 新增**日志列表页** `ConsoleLogListScreen`：列出 `devlogs/` 全部文件（按修改时间倒序），显示文件名 + 日期·字节数，点选进查看页；控制台/未连接态「查看日志」均先进列表 |
| 2 | 日志名称要以 MAC 作为区分 | 落盘文件名改为 `s7_devlog_<MAC 去冒号大写>_<时间戳>.log`；拉取时用当前连接设备地址命名 |
| 3 | 长日志没有滚动条 | 查看页加**竖向滚动条**（thumb 高∝可见比例、位置∝进度，**可拖动跳转**）；长行另有横向滚动 |
| 4 | 行号是界面的、文本要原样呈现 | 每行 `Row{ 行号 gutter Text（界面元素）+ 正文 Text（`softWrap=false` 原样）}`——行号不进正文，正文含前导空格/缩进**逐字符保留** |

**代码改动**
- `domain/DeviceLogStore.kt`（新）：app 级单例，`devlogs/` 目录；`save(bytes, mac, ts)` MAC 命名、`list()` 倒序枚举、`read(name)` 防目录穿越读取。**替换**原 `S7LogHolder`（内存单份 → 文件多份可列表）
- `ui/screen/settings/ConsoleLogListScreen.kt`（新）：列表页，注入 `DeviceLogStore`；文件日期用**本机时区**渲染（`TimeZone.getDefault()`，按每个文件时间戳取偏移，含夏令时——不经 app 的 `TimeZoneProvider` 抽象）
- `ui/screen/settings/ConsoleLogViewScreen.kt`（重写）：按文件名 IO 读取 → 按行切分 → `LazyColumn` 行号 gutter + 原样正文 + `VerticalScrollbar`
- `viewmodel/DeviceConsoleViewModel.kt`：`pullLog` 用设备 MAC 存盘、`logStore` 替换 `logHolder`
- `ui/nav/Routes.kt`：`ConsoleLogList`（object）+ `ConsoleLogView(fileName)`；`BlueTraceApp.kt` 三级导航（控制台 → 列表 → 查看）
- 未连接态「查看日志」入口——支持**离线**翻看历史日志

**真机验证**（Redmi）：列表展示两条不同 MAC 文件（`71614819FCC4` 102088 B / `C8D3E0C1D8F7` 82 B），手动点选任意条进查看页；长日志 1200 行行号 gutter + 竖/横滚动条正常；短日志缩进行 `    indented line kept verbatim` 前导空格逐字符保留、尾部空行如实呈现。截图 `assets/h_*.png`

## 2026-07-03 · 日志查看页（第 8 轮）

- 设备日志区**去掉「已保存: <路径>」行**（保存路径仍由 toast 提示）
- 拉取完成后出现「**查看日志**」按钮 → 新增**日志查看页**：标题 + 文件名 + 行数/字符数 + 逐行带行号等宽渲染（LazyColumn 承载上万行，单行可横向滚动）
- 跨页共享用 app 级单例 `S7LogHolder`（控制台拉完写入，查看页读出）
- 真表验证（S7-FCC4）：拉回 2238 块/498765 B → 查看页显示 9305 行真实固件日志（AlarmTimer/ADM_SaveWearCacheData/ActivityData…）。截图 `assets/g_*.png`

## 2026-07-03 · 自定义对时（第 7 轮）

- 「同步时间」拆为两个：**同步手机时间**（原行为）+ **自定义对时**
- 自定义对时对话框：可填 年/月/日/时/分/秒 + **时区(小时)**，写入设备——**测跨时区 / 过零点**
- 「填手机当前时间」一键预填；星期由 y/m/d 自算（Zeller）；字段越界自动夹紧
- shared 层 `S7Console.setDateTime(dt)`（syncTime 复用）；Mock 手表记住被设时间冻结回读
- 单测 `setCustomDateTime_writesAndReadsBack`（含过零点 23:59:58 + 跨时区 tz=-5，周四=4 自算）
- **真表验证**（S7-FCC4）：写入 2026-07-03 23:25:30 → 设备时间即显示该值、与手机偏差 +50355 秒（≈14h），确认自定义时间已写入真表并按其走时。截图 `assets/f_*.png`

## 2026-07-02 · 连接页排序细化（第 6 轮）

- **不支持设备**：去掉右侧按钮 + 去掉「不支持」标签，仅名称灰显（无文本、无按钮）
- **排序**：同一列表内 已连接置顶 → 支持的在上、不支持自然下沉 → 按信号强度（RSSI 降序）
- **防跳动**：扫描更新经 `sample(1s)` 节流，列表最多 1 次/秒按信号重排，可稳定点选
- 真机验证：多台 SKG 手表按 -18…-80 dBm 降序排列；不支持的温度计(-38dBm 强信号)仍沉底、无按钮无标签、名称灰显。截图 `assets/e_*.png`

## 2026-07-02 · 连接页交互重做（第 5 轮）

针对连接体验的一组改进：

| # | 反馈 | 改动 |
|---|------|------|
| 1 | 「切换/连接设备」按钮不要，点整卡进入 | 头卡整卡可点 → 连接页；卡上加 `›` + 「点此卡片连接/切换设备」提示，去掉独立按钮 |
| 2 | 进入除非明确断开否则不要断开 | 连接页**取消自动断开旧设备**——单点即连/断该设备；多台由控制台选择控制哪台（可多台并存） |
| 3 | 已连接设备置顶、无名设备不显示 | 排序仅「已连接置顶」；过滤掉无名 `(unnamed)` 设备 |
| 4 | 按名称 / 信号强度过滤 | 连接页加名称/MAC 过滤框 + RSSI 滑条（复用设备连接页样式） |
| 5 | 有数据就刷新导致跳动 | **稳定排序**：不再按 RSSI 实时重排（保留发现顺序，信号值变化不移动行） |
| 6 | 不支持的设备不要运行连接 | 非 B2A 设备展示为「不支持」灰标，不可点连接；`toggleConnect` 对不支持设备直接忽略 |
| 7 | 右侧「—」含糊 | 右侧改为明确的 **连接 / 断开 / 连接中…** 按钮（连接=蓝，断开=红） |

**代码改动**：`ConsoleConnectViewModel`（过滤/稳定排序/单设备 toggle/supported 标记）、`ConsoleConnectScreen`（过滤 UI + ActionChip 连接按钮 + 不支持标注）、`DeviceConsoleScreen`（头卡整卡可点，`Section` 加 onClick）、strings。
**真机验证**（多台 SKG 手表环境）：名称过滤、RSSI 滑条、无名/不支持设备处理、连接→按钮变红「断开」、头卡整卡进连接页全部通过。截图 `assets/d_*.png`。

## 2026-07-02 · 控制台体验优化（第 4 轮）

针对真机使用反馈的一组改进（本轮）：

| # | 反馈 | 改动 |
|---|------|------|
| 1 | 如何连接其他设备？ | 已连接态头部加「**切换 / 连接设备**」入口（原先只有未连接态才有）；进内置连接页 |
| 2 | 「刷新全部」是刷新设备信息，为何不在设备信息栏 | 刷新按钮从头部大按钮**移入「设备信息」栏标题行**（右侧「刷新设备信息」文字动作），改名去歧义 |
| 3 | 发指令要有成功提示 | 全部指令加**土 toast** 反馈（已同步时间/已写入用户/已开始/停止查找/已刷新/已导出/失败原因）；VM 加 `ConsoleToast` 一次性事件流 |
| 4 | 写用户要能修改 | 用户信息改为「**编辑并写入**」——弹表单（身高/体重/性别/生日可改）后写设备；保留「写入当前采集用户」快捷键 |
| 5 | 拉的是哪个日志、存哪里 | 日志区加说明「拉取**设备固件运行日志**（now+bak）」；**改存到公共 `Download/BlueTrace/logs/`**（原存 app 私有目录），拉取后显示完整路径 + toast |
| 6 | 找手表能否停止 | 确认可停（toggle「找手表 / 结束找表」）；查找态加高亮 + 提示「再次点击停止震动」 |
| 7 | 操作日志能否导出 | 「操作日志」栏标题加「**导出**」→ 落 `Download/BlueTrace/logs/s7_oplog_*.log` |

**代码改动**
- `viewmodel/DeviceConsoleViewModel.kt`：`ConsoleToast` 事件流；`writePerson(person)` 编辑写入；`exportOpLog()`；日志改经 `MediaStoreExporter` 导出公共目录；注入 `exporter` 替代 `logsDir`
- `ui/screen/settings/DeviceConsoleScreen.kt`：toast 收集（土 toast）；头部切换入口；`Section` 支持标题栏动作；`PersonEditDialog` 编辑表单；日志说明；操作日志导出
- `data/android/MediaStoreExporter.kt`：加 `exportLogBytes` 字节精确导出重载
- `di/AppModule.kt`：VM 注入 exporter
- `res/values{,-en}/strings.xml`：新增控制台文案与 toast 文案

**真机验证**（SKG WATCH S7-FCC4）：切换入口、刷新入栏、编辑写入 + toast「已写入用户信息」、操作日志导出 + toast「已导出到 Download/BlueTrace/logs/…」全部通过；导出文件确认落盘。截图见 `assets/c_*.png`。

**文档**：新增 [command-status.md](command-status.md) + [command-status.html](command-status.html)（B2A 全指令实现状态：维护指令全实现，除 OTA 二期外未实现项均为业务类/固件未开/可选诊断）。

## 2026-07-02 · 真实蓝牙 + 联调（第 3 轮）

- `AndroidBleClient`：真实 GATT（扫描广播 UUID 识别 / MTU 247 / 服务发现 / CCC 订阅 / WriteNoRsp）
- 纯真实扫描（去 Mock 合流，nRF 式全量展示含无名设备）
- 控制台内置连接页（非参考限连 1 台）+ 多设备先选择
- 真机联调全通：GET 全套、对时、拉日志 431KB、危险命令断链判据
- 协议实证修正：MAC LE 反序、DevType device_type 码 hex、ASCII 尾部截断
- Android 侧工程坑（定位静默过滤 / 扫描节流 / GATT 泄漏）已**网络核查**并修正归因，见 [completeness-audit.md](completeness-audit.md)

## 2026-07-02 · 设计 + shared 实现 + Mock 联调（第 1–2 轮）

- 9 代理深读 S7 B2A 协议文档 + 交叉校验完整性 → protocol-spec / completeness-audit / plan
- shared 层 S7 协议栈（帧编解码 / 消息 / 控制会话 / Mock 手表）+ 36 例单测
- 控制台 UI（工程风格）+ Mock 联调全流程
- 19 代理多维评审 → 15 项确认全修复（见 [review-report.md](review-report.md)）
