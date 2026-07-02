# S7 设备维护控制台 · 修改记录

> 本文件记录设备维护（DUT）控制台从设计到真机联调再到体验优化的关键改动。
> 完整设计见同目录 [protocol-spec.md](protocol-spec.md) / [plan.md](plan.md) / [command-status.md](command-status.md)。

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
