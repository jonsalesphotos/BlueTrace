# BlueTrace 真机使用中发现问题 → 修复 · Agent Prompt（复用模板）

> 自己用 app 时发现问题，复制 §一 模板填【】喂给 `/goal`。**一次填一个问题**（便于小步单 commit）。
> 设备已连：**Xiaomi M2101K9C · `adb -s 5d510bb4`**（adb 在 `C:\Users\260164\AppData\Local\Android\Sdk\platform-tools\adb.exe`）。BLE/DUT 仍 **Mock**；个人仓库**直接 push main**。

---

## 一、复制这段，填【】后发给 /goal

```
/goal 依据 Docs/agent_fix_prompt.md 修一个我用 app 时发现的问题。先在真机(5d510bb4)复现取证、定位根因、最小修复、单测+真机回归验证、push main。

【问题】
- 屏幕/位置：【哪个 Tab / 屏 / 元素，如 设置·语言 / 采集结束摘要的"修改采集人"按钮】
- 复现步骤：【1… 2… 3…】
- 期望：【应该怎样】
- 实际：【实际怎样；有截图/报错就贴】
- 频率·严重度：【必现/偶现 · 崩溃/功能错/数据错/视觉/文案】
- 当时状态(可选)：【深色还是亮色 / 中文还是英文 / 选了哪个用户·场景 / 刚做了什么】
```

> 可以直接把**截图**一起发给 agent（它能读图）。问题里能给越具体的复现步骤越好。

---

## 二、Agent 执行流程（固定）

**0. 先判性质（别盲修）**
- 是**真 bug**（实现偏离 `SPEC.md` / `prototypes/v4_android.html` 原型）→ 修。
- 是**已知未实现/本期 OUT**（如 离线采集 A/B/C 待协议、真 i18n locale 切换）→ 说明它是有意延后的，不当 bug 修。
- 是**设计取舍/偏好**（改了会动设计）→ **先 `AskUserQuestion`**，不擅自改设计。
- 是**预期行为被误解** → 解释清楚并停。

**1. 真机复现 + 取证**（设备已连，优先真机而非空想）
- `adb -s 5d510bb4` 重启/点按复现：`am force-stop io.bluetrace` → `am start` → `input tap/swipe` 走到问题点 → `screencap -p` + `pull` 对照原型。
- 拉诊断证据：
  - 应用日志（含崩溃堆栈）：`adb -s 5d510bb4 pull /sdcard/Android/data/io.bluetrace/files/logs/`（`app-YYYY-MM-DD.log`）。
  - logcat：`adb -s 5d510bb4 logcat -d | findstr -i bluetrace`（崩溃/异常）。
  - 用户库：`adb -s 5d510bb4 shell run-as io.bluetrace ls -l databases/`（`bluetrace.db`：`subject` / `app_meta`）。
  - 会话产物：导出在 `/sdcard/Download/BlueTrace/`，会话夹 `主场景_子场景_用户_日期_时间_MAC后四位` + `session_manifest.json`。
  - 深色/语言相关 → `cmd uimode night yes/no`、切语言复现。
- **复现不了** → 别硬改；把已试步骤回报，问更多细节。

**2. 定位根因**（到 file:line，解释"为什么错"，不是改表象）
- 复杂/多嫌疑点时可用 **Workflow** 并行搜 + 对抗式核验根因（确认是它再动手）。

**3. 最小修复**
- scoped、只改相关处；**不顺手重构**、不碰无关屏；纯逻辑放 shared（可单测）。
- 守红线：屏内**无说明性副标**（解释进注释，见 `memory/ui-no-explanatory-subtitles`）；语言**仅中/英**；文件名/场景 **token 恒英文**。

**4. 验证（硬要求）**
- 能复现该 bug 的**单测**（commonTest，进 `:shared:jvmTest`）——尤其逻辑/命名/状态机类。
- `./gradlew :shared:jvmTest` + `:app:assembleDebug` **绿**。
- `./gradlew installDebug` 装回真机 → **真机回归该问题路径**（截图/文件证据证明已修）。
- 关键闭环**不回归**：扫描→连接→采集→结束→数据→导出；深色/外观/启动屏。

**5. 提交**
- **单 bug 单 commit**：`fix(scope): 简述`，正文写**根因 + 修法 + 验证证据**；push main。
- commit 末尾必须：`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

---

## 三、屏 ↔ 代码 速查（定位用）

| 现象在哪 | 看这些 |
|---|---|
| 采集主界面（设备/用户/采集场景/在线·离线） | `app/.../ui/screen/collect/CollectHomeScreen.kt` + `viewmodel/CollectViewModels.kt` |
| 场景选择（主/子场景） | `ui/screen/.../SceneSelect*` + shared `scene/SceneCatalog`（`app/src/main/assets/scenes.json`） |
| 用户 选择/编辑（Default/DatePicker/Slider/删除） | `app/.../ui/screen/subject/SubjectScreens.kt` + `data/.../SqlDelightSubjectRepository.kt` |
| 采集运行 / 采集类型 / GNSS 勾选 | `ui/screen/run/CollectionRunScreen.kt` + `CollectTypeSheet.kt` |
| 结束摘要 / 会话详情 / 改采集人·场景 | `ui/screen/summary/SessionSummaryScreen.kt`、`ui/screen/data/SessionDetailScreen.kt`、shared `SessionStore.editSession` |
| 文件夹命名（5 段） | shared `data/SessionLayout.kt`（`sessionFolderName`） |
| 数据 Tab / 列表 / 导出 | `ui/screen/data/DataHomeScreen.kt` + `MediaStoreExporter` |
| 设置 / 外观 / 语言 / 应用日志 | `ui/screen/settings/SettingsScreens.kt`、`AppearanceScreen.kt`、`LanguageScreen`、shared `FileDiagnosticsLog` |
| 崩溃 / 异步 / 落盘 | shared `session/`（`DefaultSessionController`、`FileDiagnosticsLog`）、`BlueTraceApplication`（崩溃 handler） |
| DI / 绑定 | `app/.../di/AppModule.kt` |

文案 → `app/src/main/res/values/strings.xml`（中）+ `values-en/strings.xml`（英），两份同步。

---

## 四、构建

```bash
./gradlew :shared:jvmTest        # 逻辑单测
./gradlew :app:assembleDebug     # 构建
./gradlew installDebug           # 装回真机复测（5d510bb4）
```

> 设计/交互真源以 `SPEC.md` ＞ `prototypes/v4_android.html` 为准；拿不准是 bug 还是设计 → 先 `AskUserQuestion`。
