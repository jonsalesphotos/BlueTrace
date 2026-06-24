# BlueTrace 剩余页面 设计↔实现 同步 · Agent 构建 Prompt（v6 · 场景模型 + 采集前置流 + 摘要/详情编辑 · Workflow 编排 · 真机必测）

> 接 [v5](agent_build_prompt_v5.md)（三 Tab 主屏）。本轮同步**剩余主要页面**，核心是把"采集场景"从占位变**真模型**，并把依赖它的页面一并对齐最新原型：
> ① **场景模型**（`scenes.json` 驱动）+ **文件命名落地**（`主场景_子场景_用户_日期_MAC后四位`）；② **场景选择页**（新）；③ **用户选择 / 用户编辑** 重设计（Default 用户、日期选择器、身高/体重刻度尺）；④ **采集结束摘要 / 会话详情** 增"修改采集人 / 场景"。
>
> **权威顺序**：`SPEC.md` ＞ `Docs/prototypes/v4_android.html` ＞ 截图 `Docs/assets/screenshots/`。场景词表真源 = `Docs/architecture/scenes.json`。
> **红线**：① 屏内**无说明性副标**（解释进代码注释；`memory/ui-no-explanatory-subtitles`）；② 语言中/英、文件名/场景 token **恒英文**（`memory/language-zh-en-only`）。
> **执行模式**：`/goal` 自治 + **`Workflow` 工具编排**（§四）。BLE/DUT 仍 **Mock**；**个人仓库直接 push main**。
> **⛳ 真机测试 = 硬门（必选，非可选）**：未在真机（Xiaomi M2101K9C · Android 13）跑通关键路径并留证据，本轮**不算完成**（§六）。

---

## 一、怎么用（goal + Workflow）

```
/goal 依据 Docs/agent_build_prompt_v6.md，用 Workflow 工具编排，同步 BlueTrace 剩余页面：先落地场景模型(scenes.json 驱动)+文件命名(主场景_子场景_用户_日期_MAC后四位)，再做场景选择页、用户选择/编辑重设计、结束摘要/会话详情加"修改采集人/场景"。每改一块即 :app:assembleDebug + :shared:jvmTest 绿；最后**必须真机自测**关键路径并留截图/产物证据；直到验收清单(含真机门)全绿；按仓库习惯 push main。子决策不明先 AskUserQuestion。
```

**编排要点**：§三-0 的**场景模型 + 命名是地基**，其它页面都依赖它 → **先串行做地基**，再并行推各页面（页面间仍共享 `Routes.kt`/`strings.xml`/`AppModule.kt`/NavHost，**共享文件改动串行收尾**，避免并行写冲突）。详见 §四。

---

## 二、单一真源 / 本轮范围

**真源**
- 设计：`v4_android.html` —— 锚点屏：`采集场景选择（主·子场景）`、`用户A 用户选择`、`用户C 用户编辑`、`结束A 采集结束摘要`、`数据C 会话详情`（屏内=要对齐；`.screen-ux/.screen-note`=注释，**别照搬进屏**）。
- 场景词表：`Docs/architecture/scenes.json`（结构：`default{main,sub}`、`autoDefaultUserSubs[]`、`scenes[]`，每条 `token/zh/en + subs[]`）。
- 规格：`SPEC.md`。

**范围 IN（本轮）**
- 场景模型（shared）+ `scenes.json` 加载 + **命名落地** + manifest 记 scene。
- 场景选择页（新）、用户选择 / 用户编辑（重设计）、结束摘要 / 会话详情（加编辑）。
- 采集主界面"采集场景" tile **接真场景选择**（v5 是占位）。

**范围 OUT（本轮别做）**
- **离线采集 A/B/C**：内容待 DUT 协议冻结（设计已出流程骨架，APP **暂不建实壳**，留到协议轮）。
- **真正运行时 i18n locale 切换**（语言屏 v5 已落，本轮不深化）。
- 设备连接 / 采集运行 / 启动权限 / 横切 等**已基本一致**的屏：仅**细节核对**，不大改（运行/摘要副标里 `mode.label` 随场景模型顺带改成场景显示即可）。

---

## 三、逐项：现状 → 目标（gap）

### 0. 场景模型 + 文件命名（地基 · 先做）
**现状**：无 Scene 模型；"场景" = `CollectMode{WEAR/UNWEAR}`（`shared/.../domain/Collect.kt`）。命名 `sessionFolderName()`（`shared/.../data/SessionLayout.kt:37`）= `<Mode.fileToken>_<alias>_<yyyyMMdd>_<HHmmss>_<macShort>`，例 `Wear_shb_20260521_153000_0427`。`SessionConfig.mode` / `SessionSummary.mode`（`shared/.../domain/Session.kt`）。
**目标**：
1. **Scene 模型（shared commonMain，可 jvmTest）**：定义 `Scene`（主场景 `token/zh/en` + `subs[]{token/zh/en}`）与一个**选择** `SceneSelection(mainToken, subToken)`。从 **`scenes.json`** 加载（把 `scenes.json` 作为 **app asset**（`app/src/main/assets/scenes.json`）或 shared 资源打包，`kotlinx.serialization` 解析；保持与 `Docs/architecture/scenes.json` 同源——可在构建期/手动同步，注明）。提供 `default{Wear,Wearing}`、`autoDefaultUserSubs`。
2. **命名落地**：`sessionFolderName()` 改为 **`<mainToken>_<subToken>_<alias>_<yyyyMMdd>_<HHmmss>_<macShort>`**（5 段语义：主场景_子场景_用户_日期_MAC后四位；token 恒英文，如 `Wear_Wearing_shb_20260611_1432_de54`）。更新 `SessionLayoutTest`。
3. **模型贯通**：`SessionConfig` 用 `SceneSelection` 取代/并存 `CollectMode`（取舍你定，但**保持 SessionController 接口稳定**，且命名/manifest 必须落 scene）；`SessionSummary` 同步带 scene；manifest 记 `mainScene/subScene`（英文 token）。
4. **Default 用户 + autoDefaultUserSubs**：引入"**Default**"伪用户（非真人，纯采数据）；当所选**子场景 ∈ `autoDefaultUserSubs`**（Unwear/Desktop/Pocket/…）→ **自动切到 Default 用户**（`memory` 中"未佩戴自动用默认用户"裁决）。

### A. 场景选择页（新 · `采集场景选择`）
**现状**：无；采集主界面"采集场景" tile 点击仅 Toast 占位（`collect_scene_todo`）。
**目标（原型 `采集场景选择`）**：两级选择——**主场景**横向/列表（HR/SpO2/Wear/Step/Sleep/Stress/Swim，来自 JSON）+ 选中主场景后列其**子场景**；选中即写入本次 `SceneSelection`，回采集主界面更新 tile 值（中文环境显示 zh "佩戴 · 佩戴中"，token 英文）。`autoDefaultUserSubs` 命中时按 §0.4 自动切 Default 用户。`Route.SceneSelect` + 接入采集主界面 tile 点击。

### B. 用户选择 重设计（`用户A`）
**现状**（`app/.../ui/screen/subject/SubjectScreens.kt:63`）：当前/其他两分区、**点行即选即返回**、无确认按钮、无 Default。
**目标（原型 `用户A`）**：① 顶部 **Default 行**（显示器图标 + "默认"标签，非真人）；② 单一"用户"区**单选**（radio）shb/lina/…；③ 底部两按钮 **`+ 新建用户`** + **`确认`**（未选时确认置灰；返回与确认等效；可不选任何人只走返回，见原型注释）。④ 选 Default → 用于无人/纯数据采集。去掉旧"当前/其他"分区与即点即返回。

### C. 用户编辑 重设计（`用户C`）
**现状**（`SubjectScreens.kt:147`）：alias/sex(分段)/birth(**纯文本**)/height(**纯数字框**)/weight(**纯数字框**)。
**目标（原型 `用户C`）**：① 别名占位提示 `仅 A–Z a–z 0–9`；② **出生年月 → 日期选择器**（M3 `DatePicker`/`rememberDatePickerState`，显示如 `1992-05` + 日历图标的可点字段；本项目暂无 DatePicker，新引入即可）；③ **身高 / 体重 → 刻度尺/Slider**（**各占一行**，值标签如 `身高 · 175 cm` / `体重 · 75.0 kg`；原型为带刻度的尺子，M3 `Slider`（已在 `DeviceConnectScreen` 用过）或自定义刻度尺均可）；④ 保存回选择页并自动选中。删旧"如 shb（建议别名…）"等说明。

### D. 采集结束摘要 加"修改采集人 / 场景"（`结束A`）
**现状**（`app/.../ui/screen/summary/SessionSummaryScreen.kt:54`）：摘要卡（时长 + `alias · mode.label · …` + pills）+ 文件区 + 查看详情/导出；**无编辑**。
**目标（原型 `结束A`）**：摘要卡内加**白色圆角按钮「✎ 修改采集人 / 场景」**；点击 → 编辑（底部 sheet/弹层）重选 **用户 + 场景** → 落地：**更新 manifest（alias/scene）+ 按新命名重命名会话文件夹**（5 段）。副标 `mode.label` → 场景显示（`佩戴 · 佩戴中`）。

### E. 会话详情 加"修改采集人 / 场景"（`数据C`）
**现状**（`app/.../ui/screen/data/SessionDetailScreen.kt:44`）：manifest 摘要（含"用户/模式"）+ 文件选择导出；**无编辑**。
**目标（原型 `数据C`）**：manifest 卡底部加**整行按钮「✎ 修改采集人 / 场景」**（事后再归类）；点击 → 同 D 的编辑 → 同样落 manifest + 重命名文件夹。manifest 摘要的"用户/模式"行改为"采集人 · 场景"（`shb · 佩戴·佩戴中`）。

> 编辑落地口径（D/E 共用）：改用户/场景 = 重写该会话 `manifest`（alias + mainScene/subScene 英文 token）**并**把会话文件夹重命名为新 5 段名；若重命名冲突/失败，回滚并提示。原始 HEX 等内容不动。

---

## 四、编排（Workflow 模式 · 核心）

> 共享文件：`Routes.kt`（+`SceneSelect`）、`strings.xml`(+`values-en`)、`AppModule.kt`（Scene 仓库 / VM）、`BlueTraceApp.kt`(NavHost)。**禁止并行写**这些。

推荐相位（用 `Workflow`，按需增删 agent）：
- **Phase 0 «地基»（串行）**：场景模型 + `scenes.json` 加载 + 命名落地 + 模型贯通 + 单测。**先绿再往下**（`:shared:jvmTest`）。
- **Phase 1 «Plan»（`parallel` 只读）**：A 场景选择页 / B 用户选择 / C 用户编辑 / D 摘要编辑 / E 详情编辑 —— 各 agent 读原型该屏 + 现状代码，产出**结构化改动清单**（标出落在共享文件的 edit）。零冲突。
- **Phase 2 «Apply»（串行）**：按 plan 落盘；**共享文件一次性合并改**；私有屏文件分别改。每块改完跑 `:app:assembleDebug` + `:shared:jvmTest`。
- **Phase 3 «Verify»（`parallel` 对抗）**：(a) 逐屏对照原型结构；(b) 红线#1 扫屏内副标 + 语言无跟随系统 + token 恒英文；(c) 命名/ manifest 5 段正确（含编辑后重命名）；(d) 构建 + 单测绿、无死代码（旧 `mode.label` 残留、`ModeFilter` 等）。汇总必修 → 修 → 复跑至净。
- **Phase 4 «真机»（串行 · 硬门）**：见 §六，**必须**真机跑通并留证据。

---

## 五、不做（OUT）
- 离线采集 A/B/C 实壳（待 DUT 协议）；真 i18n locale 切换；服务器/上传等二期。
- 不动 V4 已定 IA（扁平设备+限额、三 Tab、硬锁定）、不改 BLE/DUT（Mock）。
- 不顺手重做设备连接/采集运行/启动权限/横切屏（仅 `mode.label`→场景显示的顺带改）；要大改先 `AskUserQuestion`。

---

## 六、真机自测（硬门 · 必选 · 缺则本轮不算完成）

**设备**：Xiaomi M2101K9C · Android 13（`adb devices` 取序列号；前轮为 `5d510bb4`）。配方（参 v3 §E）：`adb -s <serial>` → `am force-stop io.bluetrace` → `am start` → 操作 → `screencap -p /sdcard/x.png` + `adb pull` → `input tap/swipe` 驱动；`cmd uimode night yes/no` 验深色。

**必测关键路径（逐条留截图/产物）**：
1. 采集主界面 → **场景选择页**：选 主场景+子场景 → 回主界面 tile 显示 `佩戴 · 佩戴中`；选"未佩戴"等 → **自动切 Default 用户**。
2. **用户选择**：Default 行可选；单选 + **确认**；**用户编辑** 日期选择器选出生、Slider/刻度尺调身高/体重、保存回选中。
3. 跑一次采集 → 结束 → 打开会话文件夹，确认**文件夹名 = `主场景_子场景_用户_日期_MAC后四位`**（5 段、token 英文）、`manifest` 记 mainScene/subScene。
4. **结束摘要**点「修改采集人/场景」→ 改 → 确认**文件夹被重命名** + manifest 更新。
5. **会话详情**点「修改采集人/场景」→ 改 → 同样落地。
6. 深色 / 外观、启动屏、扫描→连接→采集→结束→数据→导出闭环**不回归**。

> 真机证据（截图 + 关键文件夹名/manifest 片段）随提交说明附上或在回复中给出。真机不可用时**停下来报告**（不要用模拟器假装通过）。

---

## 七、验收清单（Definition of Done · `/goal` 达成条件）
1. **场景模型**：`scenes.json` 驱动；`default{Wear,Wearing}` + `autoDefaultUserSubs` 生效；主/子场景中文显示、token 英文。
2. **命名**：新会话 = `主场景_子场景_用户_日期_MAC后四位`（5 段）；`SessionLayoutTest` 更新且绿；manifest 记 scene。
3. **场景选择页**：两级选择可用，回写采集 tile；未佩戴类自动 Default 用户。
4. **用户选择/编辑**：Default 行 + 单选 + 确认；出生日期选择器、身高/体重 Slider/刻度尺；无旧说明副标。
5. **摘要/详情编辑**：两处「修改采集人/场景」可用 → 改 manifest + **重命名文件夹**。
6. **构建/测试**：`:app:assembleDebug` 成功、`:shared:jvmTest` 全绿、无死代码。
7. **红线**：屏内零说明副标；语言中/英；文件名/token 恒英文。
8. **⛳ 真机门**：§六 全部关键路径真机跑通并留证据。
9. **提交**：偏离原型处在提交说明列明；**push main**。

---

## 八、构建 / 提交

```bash
./gradlew :shared:jvmTest         # 先地基单测（命名/场景）
./gradlew :app:assembleDebug      # 构建
./gradlew installDebug            # 装真机自测（§六 必做）
```

commit 末尾**必须**：

```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

> 本轮收敛后剩：**离线采集 A/B/C**（待 DUT 协议冻结）→ 真 i18n → 协议落地换真实 DUT 解码（接口不变）→ iOS。
