# BlueTrace 设计系统

> **这份文档回答"长什么样、用什么搭"**：设计 token（色板/字号/间距）、组件目录、以及 Android ↔ iOS 的平台自适应映射。
> 这是「统一设计语言 + 平台自适应」策略的落地依据。可视化见 [../prototypes/](../prototypes/)。

> **给新手**：设计系统 = 一套**可复用的乐高积木**（颜色、字号、按钮、卡片…）。先定义积木，再用积木拼页面。好处是改一处全局生效、两个平台共用同一套积木，只在"外壳"层做平台差异。

---

## 1. 设计原则

1. **数据是主角**：多传感器波形、数值、分包流是界面核心，配色与层级都为"看清数据"服务。
2. **状态清晰**：在线/降级/异常用颜色与徽章强表达，绝不静默。
3. **统一品牌、平台自适应**：色板/卡片/数据可视化两端一致；导航/弹层/控件按平台规范。
4. **克制**：一期是可靠的采集工具，不是配置项堆砌的工具箱。

## 2. 色板（Design Tokens · Color）

### 2.1 品牌与语义色
| Token | 值 | 用途 |
| --- | --- | --- |
| `primary` | `#2BAEEA` | 主色（浅蓝），主操作/选中 |
| `primary-deep` | `#1C8EC5` | 主色深，文字/描边 |
| `primary-container` | `#DCF1FB` | 主色浅底，tonal 背景 |
| `tertiary` | `#7C5BC9` | 点缀紫，REFERENCE/次要强调 |
| `success` | `#2AAE6D` | 成功/在线/低频组帧 |
| `warning` | `#E89F40` | 警告/单路降级 |
| `error` | `#E5484D` | 错误/全局阻断/停止 |
| `surface` | `#FFFFFF` | 卡片面 |
| `bg` | `#F3F5F8` | 页面背景 |
| `on-surface` | `#1B1D22` | 主文字 |
| `on-surface-variant` | `#4C525C` | 次文字 |
| `outline-variant` | `#D7DAE0` | 描边/分割线 |

### 2.2 传感器专属色（贯穿配置/波形/分包流，务必固定）
| 传感器 | 主色 | 浅底 | 频段 |
| --- | --- | --- | --- |
| PPG | `#2AAE6D` 绿 | `#D4F4E2` | 高频·批包 |
| ECG | `#E5484D` 红 | `#FFE3E3` | 高频·批包 |
| IMU | `#2BAEEA` 蓝 | `#DCF1FB` | 高频·批包 |
| 地磁 Magnetometer | `#7C5BC9` 紫 | `#ECE3FF` | 低频·组帧 |
| 温度 Temperature | `#E89F40` 橙 | `#FFE8C9` | 低频·组帧 |

> 同一传感器在任何界面都用同一色——用户靠颜色识别数据来源。

### 2.3 暗色模式（建议二期补全）
一期以亮色为主；token 已抽象，暗色只需替换一组值（surface/bg/on-surface 反相，传感器色提亮）。

## 3. 字体与字号（Type Scale）

| 角色 | 字号/字重 | 用途 |
| --- | --- | --- |
| Display | 30–38 / 800 | Splash 品牌、计时大数 |
| Title | 16–20 / 700 | 导航标题、卡片标题 |
| Body | 13–15 / 400–600 | 正文、列表 |
| Caption | 11–12 / 500 | 副标题、状态、单位 |
| Mono | 10–12 / `ui-monospace` | MAC、Hex 分包流、数值 |

**平台字体**：Android = Roboto / 思源；iOS = **SF Pro** / PingFang。用系统字体栈，不内嵌字体。

## 4. 间距 · 圆角 · 阴影

| Token | 值 |
| --- | --- |
| 间距基准 | 4 / 8 / 12 / 16 / 24 |
| 圆角 | sm 8 · 默认 14 · lg 20 · 全圆 999 |
| 阴影 | 1 卡片浮起 · 2 弹层 · 3 手机/对话框 |

## 5. 组件目录（Component Catalog）

> 这些是跨平台共用的"积木"。实现层各平台映射到原生控件（§6）。

| 组件 | 说明 | 出现处 |
| --- | --- | --- |
| 卡片 Card | 默认/扁平/tonal/虚线占位 | 全局 |
| 列表行 ListTile | 图标 + 主副标题 + 尾部状态/箭头 | 设备/历史/文件 |
| 状态徽章 StatusPill | ok/warn/err/idle + 脉冲点 | 设备/采集 |
| 芯片 Pill / 频率徽章 | 标签、采样率、频段 | 配置/详情 |
| 开关 Switch | 传感器/算法开关 | 配置/控制面板 |
| 分段控件 SegmentedTab | 波形 ⇄ 分包流切换 | 采集 |
| 传感器卡 SensorTile | 图标+名+迷你波形+实时值 | 采集总览 |
| 大波形 WaveBig | 单路波形大图 + 实时值 | 波形视图 |
| 分包条目 Packet | 批包(紫)/组帧(绿) + Hex | 分包流 |
| 带宽条 BandwidthBar | 多段占比 + 图例 | 策略页 |
| 指标格 Metric | 键值数值格 | 策略/采集/详情 |
| Banner | 红/黄/蓝分级提示 | 异常 |
| 长按结束按钮 LongPress | 环形/线性 2 秒进度 | 采集 |
| 弹层 Sheet | 权限说明/控制面板/恢复/确认 | 全局 |
| 指令日志 CmdLog | → CMD / ← ACK 等宽 | 控制面板 |

## 6. 平台自适应映射（Android ↔ iOS）

> **核心表**：同一"积木"在两个平台映射到各自原生控件。设计稿用统一组件，开发按此表落地。

| BlueTrace 组件 | Android（Material 3） | iOS（HIG / UIKit·SwiftUI） |
| --- | --- | --- |
| 顶部导航 | `TopAppBar`（标题居左/居中） | `NavigationBar`（返回箭头+标题，可大标题） |
| 返回 | 系统返回键 / 顶栏箭头 | 顶栏箭头 + **左缘右滑手势** |
| 底部弹层 | `ModalBottomSheet` | `Action Sheet` / `.sheet`（破坏项红色置底） |
| 轻提示 | `Snackbar` | 顶部胶囊 Toast（自绘） |
| 开关 | Material `Switch` | iOS `Toggle`（绿色） |
| 分段控件 | Material `SegmentedButton` | `Segmented Control` |
| 主操作位 | 底部按钮 / FAB | 底部按钮 / 导航栏右上，**不用 FAB** |
| 单选/多选 | 圆形复选 | iOS 勾选 / 列表 checkmark |
| 加载 | Material 进度环 | iOS Activity Indicator |
| 安全区 | status/nav bar inset | 刘海·灵动岛 + Home Indicator inset |
| 列表分组 | 卡片 + 小标题 | Grouped/Inset List |
| 图标 | Material Symbols | SF Symbols（同义替换） |
| 触感 | Haptic（可选） | Haptic（按 HIG 在确认/长按时给反馈） |

**不分叉的（两端完全一致）**：色板、传感器配色、字号层级、卡片/徽章/芯片/波形/分包流/带宽条/指标格的视觉、信息架构、文案语气。

## 7. 可访问性（Accessibility）

- 色彩不作为唯一信息载体：状态同时用图标/文字（如"在线"+绿点+脉冲）。
- 正文对比度满足 WCAG AA；传感器色在浅底上配深色文字。
- 支持系统动态字号（Dynamic Type / 字体大小）——用相对字号。
- 触控目标 ≥ 44×44pt（iOS）/ 48×48dp（Android）。
- 关键操作（停止采集）有二次确认 + 触感。

## 8. 怎么从设计系统到界面（给新手的工作流）

```text
1. 定 token（本文件）         ← 颜色/字号/间距，一次定好
2. 搭组件（§5）              ← 用 token 拼出可复用积木
3. 拼页面（原型 HTML/Figma） ← 用组件拼每一屏
4. 标平台差异（§6）          ← 只在外壳层分叉
5. 交给开发 → 各平台映射原生控件
```
改需求时：能改 token 就别改组件，能改组件就别改页面——影响面最小。
