# BlueTrace 主界面三屏 设计↔实现 同步 · Agent 构建 Prompt（v5 · 采集/数据/设置 三 Tab 收敛 · Workflow 编排）

> 接 [v3](agent_build_prompt_v3.md)/[v4](agent_build_prompt_v4.md) 的"设计↔实现对照线"。`compare_design_vs_device.html` 的 A/B 已显示**设计先行于实现**。本轮 = 落地第一步：**只同步主界面三屏**（采集 Tab / 数据 Tab / 设置 Tab）到最新原型，**不**铺开场景选择页 / 离线采集 / 真 i18n / 文件命名（那些属后续轮）。
>
> **权威顺序**：`SPEC.md` ＞ `Docs/prototypes/v4_android.html`（只看三屏 + 设置G 外观 / 设置H 语言两个子页）＞ 截图 `Docs/assets/screenshots/`。
> **红线（必须遵守）**：① 屏内只放终端用户标签，**不写说明性/规格性副标**（解释进代码注释；见 `memory/ui-no-explanatory-subtitles`）；② 语言**只中/英**、**无"跟随系统"**，且文件名/场景 token **恒英文**（`memory/language-zh-en-only`）。
> **执行模式**：`/goal` 自治 + **用 `Workflow` 工具编排**（见 §四）。BLE/DUT 仍 **Mock**；个人仓库**直接 push main**（不走 PR）。

---

## 一、怎么用（goal + Workflow）

```
/goal 依据 Docs/agent_build_prompt_v5.md，用 Workflow 工具编排，把 BlueTrace 主界面三屏（采集/数据/设置）同步到 v4_android.html 最新原型：采集 Tab 的 ModeTile→采集场景 tile + 在线/离线双采集 + 删说明副标；数据 Tab 删 ALL/Wear/Unwear 筛选；设置 Tab「外观」分区→「通用」并新增语言屏（中/英单选，无跟随系统）。直到「验收清单」全绿；:app:assembleDebug 与 :shared:jvmTest 绿；按仓库习惯提交并 push main。子决策不明先 AskUserQuestion。
```

**为什么是 Workflow**：三屏可并行分析，但它们**共享几个文件**（`Routes.kt` / `strings.xml`(+`values-en`) / `AppModule.kt` / `BlueTraceApp.kt` 的 NavHost）。纯并行写会冲突。故推荐**三相**：① 并行**只读** diff/plan（每屏一 agent，产出精确改动清单，不落盘）→ ② **串行** apply（一处统一改共享文件，避免冲突）→ ③ 并行**对抗** verify。详见 §四。

---

## 二、单一真源 / 本轮范围

**真源**
- 设计：`Docs/prototypes/v4_android.html` —— 三屏锚点：`采集 Tab · 主界面`、`数据 Tab · 采集会话`、`设置 Tab`；子页：`设置G 外观主题`、`设置H 语言`。每屏看 `.phone-screen` 内（=要对齐的）与 `.screen-ux/.screen-note`（=注释，**不要照搬进 app 屏内**）。
- 规格：`SPEC.md`。场景词表：`Docs/architecture/scenes.json`（本轮**只**取 `default {main:"Wear", sub:"Wearing"}` 做展示值，不做完整选择页）。

**范围 IN（本轮=只这三屏）**
- 三个 Tab 主屏的**屏内结构对齐原型** + 让其自洽所需的**最小**模型/导航/字符串。

**范围 OUT（本轮别做，能 stub 就 stub）**
- 完整**场景选择页**（主/子场景、7 主场景、JSON 驱动）→ 采集 Tab 的"采集场景" tile 点击暂跳占位/Toast。
- **离线采集 A/B/C** 流程（点"离线采集"暂跳占位/Toast"待协议"）。
- **真正运行时 i18n 切换**（语言屏本轮至少**持久化偏好 + 选中态正确**；若顺手能接 `AppCompatDelegate.setApplicationLocales` 就接，别为此卡住）。
- **文件命名落地**（`主场景_子场景_用户_日期_MAC后四位`）、`CollectMode→Scene` 模型大重构。

---

## 三、逐屏：现状 → 目标（gap）

### A. 采集 Tab — `app/.../ui/screen/collect/CollectHomeScreen.kt` + `viewmodel/CollectViewModels.kt`
**现状**：DeviceTile / UserTile（副标 `采集用户档案 · 写入文件名`）/ **ModeTile**（WEAR·UNWEAR 切换，副标 `… · 自动命名会话文件夹`）/ 单个 `开始采集` 按钮。
**目标（原型 `采集 Tab · 主界面`）**：
1. **删红线副标**：UserTile 副标 `采集用户档案 · 写入文件名` → **`采集用户信息`**；ModeTile 的 `自动命名会话文件夹` 副标**删除**。
2. **ModeTile → 采集场景 tile**：标题 `采集模式`→**`采集场景`**；不再是 WEAR/UNWEAR 两个按钮，而是**显示场景值** `佩戴 · 佩戴中`（主场景·子场景；中文环境显示 zh，token 恒英文 `Wear/Wearing`）；整行可点（tap→场景选择页属 **OUT**，本轮跳占位/Toast 或保留最小选择均可，但**默认呈现必须是"场景值"样式**）。底层可暂用 `CollectMode` 当桥（WEAR→`佩戴·佩戴中` / UNWEAR→`佩戴·未佩戴`），**不**强制删 `CollectMode` 模型（`SessionSummary.mode`/命名仍用）。
3. **在线/离线 双采集**：底部主按钮 `开始采集` → **`在线采集`**（沉底主按钮）；其**上方**加一个**小入口** `离线采集`（不占主位、不常按；点击属 OUT，跳占位/Toast）。对齐原型底部布局。
4. UserTile 值=alias、下方 chips=`Male/1992-5/175cm/75.0kg`（已基本有，核对样式）。

### B. 数据 Tab — `app/.../ui/screen/data/DataHomeScreen.kt` + `viewmodel/DataViewModel.kt`
**现状**：搜索框 + **ModeFilter（ALL/WEAR/UNWEAR）筛选 pill 行** + 会话卡（按日期分组、多选、导出）。
**目标（原型 `数据 Tab`）**：**删掉整条 ModeFilter 筛选行**（原型已去；筛选后期再做）。搜索 / 日期分组 / 多选 / 导出**保留不动**。
- 连带清理：`DataUiState.modeFilter`、`ModeFilter` 枚举、过滤逻辑、相关 `data_filter_*` 字符串——按是否仍被别处引用决定删/留，**别留死代码**。

### C. 设置 Tab — `app/.../ui/screen/settings/SettingsScreens.kt`（+ 新增 `LanguageScreen`）
**现状**：环境与权限 / 数据(导出位置·存储占用) / 诊断与维护(应用日志·设备维护) / **外观**(主题模式→`AppearanceScreen` 已存在) / 关于 / 二期灰显。
**目标（原型 `设置 Tab` + 设置G/H）**：
1. 分区 **`外观` → `通用`**；其下含**两行**：**外观主题**（→已有 `Route.Appearance`/`AppearanceScreen`，行标题对齐原型 `外观主题`）+ **语言**（**新增**）。
2. **新增 语言**：`Route.Language` + `LanguageScreen`（参照既有 `AppearanceScreen` 范式）：单选 **`中文（简体）` / `English`**，**不要"跟随系统"**；写 `AppPreferences.language`（新增偏好）；**即时生效**（本轮可只存偏好+重组）。
3. 字符串：`strings.xml` + `values-en/strings.xml` 同步新增 `settings_sec_general`、`settings_language`、语言屏标题/选项。
- 外观屏（`AppearanceShape`）**保留三项**（亮/暗/跟随系统）——外观≠语言，别混淆。

---

## 四、编排（Workflow 模式 · 核心）

> 三屏共享 `Routes.kt` / `strings.xml`(+`values-en`) / `AppModule.kt`(注册 `LanguageViewModel`/无VM则免) / `BlueTraceApp.kt`(NavHost 注册 `composable<Route.Language>`)。**禁止**多 agent 并行写这些文件。

推荐三相（用 `Workflow`，按需增删 agent）：

- **Phase 1 «Diff/Plan»**（`parallel`，3 个**只读** agent，每屏一个）：读原型该屏（`v4_android.html` 对应 `.phone` 段 + 截图）+ 读现状代码，产出**结构化改动清单**（文件→精确 edit；标出哪些 edit 落在共享文件）。只读、零冲突。
- **Phase 2 «Apply»**（**串行**，主循环或单 agent）：按三份 plan 落盘。**共享文件（Routes/strings/AppModule/NavHost）一次性合并改**；各屏私有文件分别改。改完 `:app:assembleDebug` + `:shared:jvmTest` 必须绿。
- **Phase 3 «Verify»**（`parallel`，对抗式）：
  - (a) **逐屏对照原型**：三 agent 各核一屏的屏内结构是否=原型（tile/按钮/分区/值）。
  - (b) **红线#1 扫描**：扫三屏 app 代码，揪出任何残留说明副标（`写入文件名`/`自动命名会话文件夹` 等）与"语言含跟随系统"。
  - (c) **构建/测试**：`:app:assembleDebug` 与 `:shared:jvmTest` 绿；无残留 `ModeFilter`/死字符串。
  汇总必修 → 修 → 复跑直至干净。

（若坚持并行 apply：给每屏 agent `isolation:'worktree'`，再人工合并共享文件——通常不值，**串行 apply 更省**。）

---

## 五、不做（OUT，重申）
- 不做完整场景选择页 / 7 主场景 JSON 驱动 / 离线采集 A·B·C / 真 i18n locale 切换 / 文件命名落地 / `CollectMode→Scene` 大重构。
- 不改 BLE/DUT（Mock）、不动 V4 已定 IA（扁平设备+限额、三 Tab、硬锁定）。
- 不引入二期项（服务器/上传/远程下发）。
- 不顺手"优化"三屏之外的屏；要动先 `AskUserQuestion`。

---

## 六、验收清单（Definition of Done · `/goal` 达成条件）
1. **采集 Tab**：三 tile（设备/用户/**采集场景**）+ **在线采集**主按钮 + **离线采集**小入口；**无** `写入文件名`/`自动命名会话文件夹` 副标；采集场景 tile 显示 `佩戴 · 佩戴中`（token 英文）。
2. **数据 Tab**：**无** ALL/Wear/Unwear 筛选行；搜索/日期分组/多选/导出仍在；无 `ModeFilter` 死代码/死字符串。
3. **设置 Tab**：`通用` 分区含 **外观主题 + 语言** 两行；**语言屏 中/英单选、无"跟随系统"**，选中态正确、偏好持久化；外观屏仍三项。
4. **构建/测试**：`:app:assembleDebug` 成功，`:shared:jvmTest` 全绿。
5. **红线**：三屏屏内零说明性副标；语言无跟随系统、文件名/token 恒英文。
6. **不回归**：扫描→连接→采集→结束→数据→导出闭环正常；深色/外观、启动屏不回归。
7. **提交**：偏离原型处在提交说明列明；按仓库习惯**直接 push main**。

---

## 七、构建 / 提交

```bash
./gradlew :app:assembleDebug      # 构建
./gradlew :shared:jvmTest         # commonMain JVM 单测
./gradlew installDebug            # 装真机/模拟器自测（可选）
```

commit 末尾**必须**：

```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

> 三屏收敛通过后，下一轮再接：场景选择页（JSON 驱动 7 主场景）→ 离线采集 A/B/C → 文件命名落地 → 真 i18n。
