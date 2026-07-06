# BlueTrace 设计系统（V4）

> **这份文档回答"长什么样、用什么搭"**：设计 token（色板/字号/间距）、组件目录、以及 Android ↔ iOS 的平台自适应映射。
> 这是「统一设计语言 + 平台自适应」策略的落地依据。**权威方向 = V4（底部三 Tab：采集 / 数据 / 设置）**，可视化见 [../prototypes/v4_android.html](../prototypes/v4_android.html)。v3 仅作 legacy 视觉参考（成熟的异常态 / 采集运行 / 恢复流程可复用其外观）。

> **给新手**：设计系统 = 一套**可复用的乐高积木**（颜色、字号、按钮、卡片…）。先定义积木，再用积木拼页面。好处是改一处全局生效、两个平台共用同一套积木，只在"外壳"层做平台差异。

> **版本**：v2.0（V4 收敛）· 2026-06-16 · 配套契约见 [../reviews/BlueTrace_V4_设计契约_2026-06-16.md](../reviews/BlueTrace_V4_设计契约_2026-06-16.md)

---

## 1. 设计原则

1. **数据是主角**：多传感器波形、数值、分包流是界面核心，配色与层级都为"看清数据"服务。
2. **状态清晰**：在线/降级/异常用颜色与徽章强表达，绝不静默。
3. **统一品牌、平台自适应**：色板/卡片/数据可视化两端一致；导航/弹层/控件按平台规范。
4. **克制**：一期是可靠的采集工具，不是配置项堆砌的工具箱。
5. **中枢 + 强状态任务（V4 新增）**：底部三 Tab（采集 / 数据 / 设置）是常驻中枢；一旦进入采集运行或任一子页，**隐藏底部 Tab**，避免强状态任务被误触切走。层级靠**对比与字重**而非堆字号；密度对齐 **4/8 网格**。

## 2. 色板（Design Tokens · Color）

### 2.1 品牌与语义色
| Token | 值 | 用途 |
| --- | --- | --- |
| `primary` | `#2BAEEA` | 主色（浅蓝），主操作/选中 |
| `primary-deep` | `#1C8EC5` | 主色深，文字/描边 |
| `primary-c`（container） | `#DCF1FB` | 主色浅底，tonal 背景 |
| `on-primary-c` | `#0E5E89` | **（V4 新增）** 主色浅底上的文字 |
| `tertiary` | `#7C5BC9` | 点缀紫，REFERENCE/次要强调 |
| `tertiary-c` | `#ECE3FF` | 紫浅底 |
| `success` | `#2AAE6D` | 成功/在线/低频组帧 |
| `success-c` | `#D4F4E2` | 成功浅底 |
| `on-success-c` | `#0B5733` | **（V4 新增）** 成功浅底上的文字 |
| `warning` | `#E89F40` | 警告/单路降级 |
| `warning-c` | `#FFE8C9` | 警告浅底 |
| `error` | `#E5484D` | 错误/全局阻断/停止 |
| `error-c` | `#FFE3E3` | 错误浅底 |
| `bg` | `#F3F5F8` | 页面背景 |
| `surface` | `#FFFFFF` | 卡片面 |
| `surface-1` | `#F6F7FB` | **（V4 新增）** 中性填充·最浅（ghost 卡、内嵌格） |
| `surface-2` | `#ECEEF3` | **（V4 新增）** 中性填充·中（分段控件槽、pill 底） |
| `surface-3` | `#E2E5EC` | **（V4 新增）** 中性填充·深（开关关闭态轨道） |
| `on-surface` | `#1B1D22` | 主文字 |
| `on-surface-v`（variant） | `#4C525C` | 次文字 |
| `outline` | `#8B919C` | **（V4 新增）** 描边·强（弱信号、占位图标） |
| `outline-v`（variant） | `#D7DAE0` | 描边/分割线·弱 |

### 2.2 传感器专属色（贯穿配置/波形/分包流，务必固定）
| 传感器 | 主色 `--s-*` | 浅底 `--s-*-c` | 频段 |
| --- | --- | --- | --- |
| PPG | `#2AAE6D` 绿 | `#D4F4E2` | 高频·批包 |
| ECG | `#E5484D` 红 | `#FFE3E3` | 高频·批包 |
| IMU | `#2BAEEA` 蓝 | `#DCF1FB` | 高频·批包 |
| 地磁 Magnetometer | `#7C5BC9` 紫 | `#ECE3FF` | 低频·组帧 |
| 温度 Temperature | `#E89F40` 橙 | `#FFE8C9` | 低频·组帧 |

> 同一传感器在任何界面都用同一色——用户靠颜色识别数据来源。色 + 文字**双编码**，色彩不作唯一信息载体。

### 2.3 暗色模式（二期补全）
一期以亮色为主；token 已抽象，暗色只需替换一组值（surface/bg/on-surface 反相，传感器色提亮）。**不在一期交付范围**。

## 3. 字体与字号（Type Scale · V4 回灌）

> V4 精修：字阶收紧、**三级字重 700 / 600 / 400**，层级靠对比与字重而非加大字号。以下为权威值（取自 v4_android 原型）。

| 角色 | 字号 / 字重 | 用途 |
| --- | --- | --- |
| **Display** | 30–38 / 800 | Splash 品牌、计时大数、结束摘要时长 |
| **Title**（导航标题） | **19 / 700**（letter-spacing −.01em） | App Bar 标题 |
| **CardTitle / EntryTitle** | **14 / 600** | 卡片标题、入口中枢卡主标、列表行主标 |
| **SectionHeader**（sec-h） | 11 / 700，大写 + letter-spacing .04em | 分组小标题 |
| **Body** | 13–15 / 400–600 | 正文、卡片描述、搜索输入 |
| **Caption / Sub** | 11 / 400–500 | 副标题、状态、单位、入口卡副标 |
| **Label / Pill** | 10.5 / 600 | 芯片、采样率、频段 |
| **Tag**（传感器方标签） | 9.5 / 700 | PPG/ECG/… 色底方标签 |
| **Button** | 主 15 / 700 · 次（outline）14 / 600 | 主/次按钮 |
| **Mono** | 10–12 / `ui-monospace` | MAC、Hex 分包流、文件名、数值 |

**平台字体**：Android = Roboto / 思源；iOS = **SF Pro** / PingFang。用系统字体栈，不内嵌字体；用相对字号以支持动态字号。

## 4. 间距 · 圆角 · 阴影

| Token | 值 |
| --- | --- |
| 间距基准 | **4 / 8 / 12 / 16 / 24**（密度对齐 4/8 网格） |
| 圆角 | `sm` 8 · 默认 14 · `lg` 20 · 全圆 999 |
| 阴影 | `shadow-1` 卡片浮起 · `shadow-2` 弹层 · `shadow-3` 手机/对话框 · **`shadow-cta`（V4 新增）** 主按钮柔焦阴影 `0 6px 16px rgba(43,174,234,.28)` |

## 5. 组件目录（Component Catalog）

> 这些是跨平台共用的"积木"。实现层各平台映射到原生控件（§6）。**★ = V4 新增/补齐**，详细规格见 §7。

| 组件 | 说明 | 出现处 |
| --- | --- | --- |
| **★ 底部导航 BottomNav** | 三 Tab：采集 / 数据 / 设置；选中态药丸指示 | 三个主中枢 |
| **★ 入口中枢卡 EntryTile** | 图标 + 主副标 + 尾部值/箭头 + 可选 pill 行 | 采集中枢（设备/受试者/模式） |
| **★ 受试者卡 SubjectCard** | 受试者别名 + 性别/生日/身高/体重 pill | 采集中枢、受试者选择 |
| **★ 模式分段 ModeSegment** | Wear ⇄ Unwear 二选一 | 采集中枢、采集运行头 |
| **★ 搜索框 Search** | 放大镜 + 输入；过滤文件/设备 | 数据列表、设备扫描 |
| **★ 文件卡 FileCard** | 等宽文件名 + 传感器 tag + 大小/时长 | 数据列表 |
| **★ 设置行 SettingsRow** | 图标 + 主副标 + 尾部（箭头 / 开关 / 值） | 设置 |
| **★ 轻提示 Toast** | 底部胶囊，操作结果反馈 | 导出完成等 |
| **★ 空状态 EmptyState** | 占位图标 + 一句说明 + 可选动作 | 列表/扫描为空 |
| **★ 会话卡 SessionFolderCard** | = FileCard 语义命名；列表单位 = 会话文件夹（D-6） | 数据列表 |
| **★ manifest 摘要 ManifestSummary** | Metric 网格 + 启用配置 pill（不含链路参数） | 会话详情 |
| **★ 存储占用条 StorageBreakdown** | 多段 usage-bar + 图例 + 清理动作 | 设置·存储 |
| **★ 权限状态行 PermissionStatusRow** | ListTile + StatusPill / 行内动作 | 设置·环境与权限 |
| **★ 进度对话 ProgressDialog** | Sheet + 确定进度条 | 导出中 |
| **★ 正在采集条 GlobalCollectingBanner** | 主色实底条 + 计时 + 返回 | 采集 Tab 顶（运行中离开时） |
| 卡片 Card | 默认 / 扁平 / tonal / ghost 虚线占位 | 全局 |
| 列表行 ListTile | 图标 + 主副标题 + 尾部状态/箭头 | 设备/历史/文件 |
| 状态徽章 StatusPill | ok / warn / err / idle + 脉冲点 | 设备/采集 |
| 芯片 Pill / 频率徽章 | 标签、采样率、频段 | 配置/详情 |
| 开关 Switch | 传感器/算法/设置开关 | 配置/控制面板/设置 |
| 分段控件 SegmentedTab | ~~波形 ⇄ 分包流切换~~（V4 精简后采集运行未用，留作后期） | — |
| 传感器卡 SensorTile | 图标 + 名 + 迷你波形 + 实时值 | 采集总览 |
| 大波形 WaveBig | 单路波形大图 + 实时值 | 波形视图 |
| 分包条目 Packet | 批包(紫) / 组帧(绿) + Hex | 分包流 |
| 指标格 Metric | 键值数值格 | 采集/详情 |
| Banner | 状态提示：暂停·重连（info 蓝）/ 已停止（err 红）；**精简后不做红黄蓝多路分级**（见契约 §九） | 采集运行 |
| **★ 运行日志 RunLog** | 等宽时间戳行；坏包/CRC/解码失败/重连只记录不打断 | 采集运行 |
| **★ 设备列表 DeviceList** | 扁平行 + 状态徽章（未连/连接中/已连）；单击连接·再点断开·连接计数·≤3 台 | 设备连接 |
| 长按结束按钮 LongPress | 环形/线性 2 秒进度 | 采集运行 |
| 弹层 Sheet | 权限说明/控制面板/恢复/确认 | 全局 |
| 指令日志 CmdLog | → CMD / ← ACK 等宽 | 控制面板 |

> **已移除（对齐 D-5 / D-9）**：`带宽条 BandwidthBar` 与"打包策略页"不再作为**用户配置**组件——App 不配置 BLE 链路参数（批包/组帧/MTU/连接间隔由设备按功耗驱动）。带宽/包速率/丢包/CRC **降级为采集运行内的只读观测指标**（用 Metric 呈现），不再阻断开始。

## 6. 平台自适应映射（Android ↔ iOS）

> **核心表**：同一"积木"在两个平台映射到各自原生控件。设计稿用统一组件，开发按此表落地。

| BlueTrace 组件 | Android（Material 3） | iOS（HIG / UIKit·SwiftUI） |
| --- | --- | --- |
| **底部导航 BottomNav** | `NavigationBar`（M3，选中药丸指示） | `UITabBar` / `TabView`（SF Symbols，选中 tint） |
| 顶部导航 | `TopAppBar`（标题居左/居中） | `NavigationBar`（返回箭头+标题，可大标题） |
| 返回 | 系统返回键 / 顶栏箭头 | 顶栏箭头 + **左缘右滑手势** |
| 底部弹层 | `ModalBottomSheet` | `Action Sheet` / `.sheet`（破坏项红色置底） |
| 轻提示 Toast | `Snackbar` | 顶部胶囊 Toast（自绘，无 Snackbar 概念） |
| 开关 | Material `Switch` | iOS `Toggle`（绿色） |
| 分段控件（SegmentedTab / ModeSegment） | Material `SegmentedButton` | `Segmented Control` |
| 主操作位 | 底部按钮（**不用 FAB**，与 iOS 对齐） | 底部按钮 / 导航栏右上 |
| 单选/多选 | 圆形复选 | iOS 勾选 / 列表 checkmark |
| 加载 | Material 进度环 | iOS Activity Indicator |
| 安全区 | status/nav bar inset | 刘海·灵动岛 + Home Indicator inset |
| 列表分组 | 卡片 + 小标题 | Grouped/Inset List |
| 图标 | Material Symbols | SF Symbols（同义替换） |
| 触感 | Haptic（可选） | Haptic（确认/长按时给反馈） |

**不分叉的（两端完全一致）**：色板、传感器配色、字号层级、卡片/徽章/芯片/波形/分包流/指标格的视觉、**底部三 Tab 信息架构**、文案语气。

## 7. V4 新增组件规格（Component Specs）

> 仅 V4 新增/补齐组件的状态与变体；既有组件（Card/ListTile/StatusPill/SensorTile/Banner/LongPress…）沿用现状。

### 7.1 底部导航 BottomNav
- **用途**：采集 / 数据 / 设置 三个常驻中枢之间切换。
- **变体**：固定 3 项（不可增减）。
- **状态**：`default`（图标 + 文字，on-surface-v）/ `active`（图标药丸底 `primary-c` + `primary-deep` 文字）。
- **行为**：仅在三个**顶级中枢**显示；进入任一子页或采集运行时**隐藏**（push 页带返回）。采集运行中若离开，"采集"Tab 顶部显示"正在采集"提示条可回。
- **Token**：`surface` 面 + `outline-v` 顶边；触控目标 ≥ 48dp / 44pt。
- **A11y**：role = tab；选中项 `aria-selected`；图标 + 文字双编码。

### 7.2 入口中枢卡 EntryTile
- **用途**：采集中枢里"设备 / 受试者 / 采集模式"等可点入子页的入口。
- **结构**：`ico`（34×34 圆角，tonal 底）+ `body`（ttl 14/600 + sub 11/400）+ `trail`（值 + 箭头）+ 可选 `pill-row` / `tag-row`。
- **变体**：`primary` / `tertiary` / `success` 图标底色；尾部可为 `值+箭头`（设备/受试者）或内联控件（模式用 ModeSegment）。
- **状态**：`default` / `pressed` / `disabled`（环境未就绪时"设备"灰显并引导权限）。
- **A11y**：整卡可点（role=button），尾部值作为状态播报。

### 7.3 受试者卡 SubjectCard
- **用途**：展示/选择采集受试者（写入文件名与 manifest）。
- **结构**：别名（如 `shb`）+ pill 行：性别 / 生日(YYYY-M) / 身高cm / 体重kg。
- **变体**：`summary`（中枢卡尾部显示别名）/ `full`（受试者选择页整卡）/ `editable`（编辑态带字段）。
- **状态**：`selected` / `unselected` / `empty`（无受试者 → EmptyState"新建受试者"）。
- **隐私**：本地存储，建议用别名而非真实姓名；一期不上传。
- **A11y**：字段以"键：值"播报。

### 7.4 模式分段 ModeSegment（Wear / Unwear）
- **用途**：标注本次采集受试者是否佩戴设备（PPG 评测需"佩戴/未佩戴"基线对照）。
- **变体**：二选一 `Wear` / `Unwear`，会话级。
- **状态**：选中段 `primary` 实底白字；未选 `on-surface-v`。运行中只读显示在采集运行头（`shb · Wear`）。
- **影响**：写入文件名前缀与 manifest；数据 Tab 可按模式筛选。
- **A11y**：role = radiogroup。

### 7.5 搜索框 Search
- **用途**：过滤文件 / 用户 / 设备。
- **结构**：放大镜 + 输入 + 可选清除。
- **状态**：`empty`（placeholder）/ `typing` / `no-result`（联动 EmptyState）。
- **A11y**：label "搜索"；清除按钮可达。

### 7.6 文件卡 FileCard
- **用途**：数据 Tab 列出一次**会话**（D-6：一会话 = 一文件夹）。
- **结构**：等宽会话名（12.5/700 mono，可换行）+ 传感器 tag 行 + 尾部 `大小 · 时长`。
- **变体**：`default` / `selected`（多选态描边 + 勾选）/ `degraded`（含掉线段，warn 角标）。
- **状态**：`default` / `pressed` / `selected` / `selecting`（进入多选）。
- **说明**：展示的是会话（文件夹），点击进会话详情看夹内构成（原始 HEX 日志 + 解码 CSV + 组合包 CSV + manifest）。
- **A11y**：选中态 `aria-checked`；名称 + tag + 大小播报。

### 7.7 设置行 SettingsRow
- **用途**：设置 Tab 的条目。
- **变体**：`navigate`（尾部箭头 → 子页）/ `toggle`（尾部 Switch，如 GNSS）/ `value`（尾部只读值，如导出位置）/ `info`（关于）。
- **状态**：`default` / `pressed` / `disabled`（二期项灰显 + "二期"标注）。
- **A11y**：toggle 行 role=switch 且状态播报。

### 7.7.1（GNSS 行 · 一期正式功能）
- GNSS 设置行为 `toggle` 变体，开启即把本机 GPS 作为一路数据源写入会话（独立 CSV + manifest）。需 while-in-use 定位 + 前台服务 location 类型；缺权限时行内显示"需定位权限"并引导。

### 7.8 轻提示 Toast
- **用途**：操作结果反馈（导出完成等）。
- **变体**：`success`（默认，绿勾）/ `info` / `error`。
- **状态**：`enter`（淡入上移）→ 停留 ~2s → `exit`。不承载关键阻断信息（阻断用 Banner）。
- **A11y**：role=status（aria-live polite）。

### 7.9 空状态 EmptyState
- **用途**：列表/扫描/搜索无内容时的占位，避免空白。
- **结构**：占位图标（`outline` 色）+ 一句说明 + 可选主动作。
- **场景**：设备扫描无结果（"未发现 BlueTrace 设备 · 重新扫描"）/ 数据为空（"还没有采集数据 · 去采集"）/ 受试者为空（"新建受试者"）/ 搜索无结果。
- **A11y**：说明文字可读；动作按钮可达。

### 7.10 会话卡 SessionFolderCard（= FileCard 的语义命名）
- **用途**：数据 Tab 列出一次**会话（D-6：一会话 = 一文件夹）**；与 §7.6 FileCard 为同一组件，强调"列表单位是会话文件夹而非扁平文件"。
- **结构**：会话名（`<Mode>_<alias>_<时间>_<设备>`，无文件扩展名）+ 传感器/GNSS tag 行 + 尾部 `大小 · 时长`；按日期分组（date 小标题）。
- **状态**：`default` / `selected`（多选态 .chk）/ `degraded`（含掉线段 warn 角标）。
- **说明**：禁止以扁平 `.rawdata` 文件作为主模型；点击进会话详情看夹内构成。

### 7.11 manifest 摘要 ManifestSummary
- **用途**：会话详情顶部展示 `session_manifest.json` 关键元数据。
- **结构**：卡内 Metric 网格 —— 起点 wallclock / monotonic 对齐锚点 / 受试者(别名·Wear/Unwear) / 设备角色(DUT+REFERENCE)；下接"本次启用配置"pill 行（传感器+采样率+设备端算法）。
- **约束**：**不含任何 BLE 链路参数**（批包/组帧/MTU/连接间隔，D-5）。
- **A11y**：以"键：值"播报。

### 7.12 存储占用条 StorageBreakdown
- **用途**：设置 Tab"存储占用"按文件类别拆分占比。
- **结构**：水平多段条 `usage-bar`（原始 HEX 日志 / 解码 CSV / 组合包兼容 CSV / GNSS CSV / manifest）+ 图例 `legend-dots` + Metric（总占用 / 会话数）+「清理已导出会话」动作。
- **配色**：原始 HEX 用 `warning`（主体强调）、解码 CSV 用 `success`、组合包 `primary`、GNSS `tertiary`、manifest `outline`。

### 7.13 权限状态行 PermissionStatusRow
- **用途**：环境与权限检查详情逐项展示。
- **结构**：ListTile（图标 + 主副标）+ 尾部 = StatusPill（`ok` 就绪 / `warn` 缺失）或行内动作小按钮（`.btn.xs` "去开启 / 授权"）。
- **分组**：硬性条件（蓝牙 / BLE 扫描 / BLE 连接 / 定位·GNSS，缺失则阻断采集）/ 建议条件（通知，可继续）。
- **A11y**：状态色 + 文字双编码；动作按钮可达 ≥ 44pt。

### 7.14 进度对话 ProgressDialog
- **用途**：导出等可观测耗时操作的确定性进度。
- **结构**：Sheet（grab + 标题 + 说明）内 `progress-track`（确定进度填充）+ "百分比 · 已处理/总量" + 取消。
- **状态**：`determinate`（已知总量，导出打包）；失败转 Banner（`error`）或 Toast（`error`），不滞留 Dialog。
- **A11y**：role=progressbar，aria-valuenow。

### 7.15 正在采集提示条 GlobalCollectingBanner
- **用途**：采集运行中用户离开运行页回到采集 Tab 时，顶部常驻"正在采集"提示，点击可回。
- **结构**：主色实底条（脉冲点 + "正在采集 · 受试者 · Wear/Unwear · 计时" + 右侧"返回采集 ›"）。
- **位置**：仅出现在**采集 Tab 顶级**（保留底部 Tab，采集项 active），置于状态栏与 App Bar 之间；其余中枢不显示。
- **A11y**：role=status；整条可点回采集运行。

## 8. 可访问性（Accessibility）

- 色彩不作为唯一信息载体：状态同时用图标/文字（如"在线"+绿点+脉冲）。
- 正文对比度满足 WCAG AA；传感器色在浅底上配深色文字（已配 `on-*-c` token）。
- 支持系统动态字号（Dynamic Type / 字体大小）——用相对字号。**注意**：V4"采集页一页放下不滚动"在大字号下需允许降级为可滚动，避免裁切（见契约"待补状态"）。
- 触控目标 ≥ 44×44pt（iOS）/ 48×48dp（Android），含 BottomNav 项。
- 关键操作（停止采集）有二次确认（长按 2 秒）+ 触感。

## 9. 怎么从设计系统到界面（工作流）

```text
1. 定 token（本文件）         ← 颜色/字号/间距，一次定好
2. 搭组件（§5 / §7）         ← 用 token 拼出可复用积木
3. 拼页面（V4 原型 HTML）     ← 用组件拼每一屏（底部三 Tab 中枢）
4. 标平台差异（§6）          ← 只在外壳层分叉（BottomNav 等）
5. 交给开发 → 各平台映射原生控件
```
改需求时：能改 token 就别改组件，能改组件就别改页面——影响面最小。

## 10. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.x | — | v3 向导式 IA 配套设计系统（已 legacy） |
| **v2.0** | 2026-06-16 | **收敛到 V4**：新增 BottomNav 与底部三 Tab 原则；回灌 V4 字阶（19/14 + 三级字重 700/600/400）与新增 token（surface-1/2/3、on-primary-c、on-success-c、outline、shadow-cta）；补齐 EntryTile / SubjectCard / ModeSegment / Search / FileCard / SettingsRow / Toast / EmptyState；按 D-5/D-9 移除 BandwidthBar/打包策略配置（降为只读观测）；GNSS 升为一期正式功能（SettingsRow toggle） |
| **v2.1** | 2026-06-16 | **数据闭环 / 设置子页 / 横切补齐**：新增 §7.10–7.15 组件规格 SessionFolderCard（= FileCard，会话文件夹语义）/ ManifestSummary / StorageBreakdown / PermissionStatusRow / ProgressDialog / GlobalCollectingBanner；配套 v4_android 原型补画数据 Tab（会话详情 + 多选 + 空态 + 搜索无结果 + 导出态）、设置子页（环境权限 / GNSS 三态 / 存储占用 / 关于）、横切（红黄蓝异常 / 进程恢复 / 正在采集条 / 协议不匹配 / 重连对账） |
