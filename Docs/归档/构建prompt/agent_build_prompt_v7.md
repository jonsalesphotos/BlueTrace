# BlueTrace 存储 & 日志 重构落地 · Agent 构建 Prompt（v7 · SQLDelight 用户表 + 滚动 .log · Workflow 编排 · 真机必测）

> 基础设施轮（与 UI 收敛线 v5/v6 独立）。**实现** [`architecture/storage_logging_design.md`](architecture/storage_logging_design.md)（v8 · 已过多视角审查修订）：① 应用日志 内存 → **滚动 .log 文件**；② 用户 Subject DataStore-JSON → **SQLite(SQLDelight)**。偏好仍 DataStore、会话原始数据/manifest 仍文件。
>
> **权威**：`storage_logging_design.md`（本轮设计真源，含审查修订口径）＞ `SPEC.md`。阶段=**demo，直接替换不迁移**。
> **执行模式**：`/goal` 自治 + **`Workflow` 工具编排**。个人仓库**直接 push main**。
> **⛳ 真机测试 = 硬门（必选）**：日志/DB 落地须在真机（Xiaomi M2101K9C · Android 13）`adb pull` 取证（§六）；真机不可用则**停下报告**，不得跳过。

---

## 一、怎么用（goal + Workflow）

```
/goal 依据 Docs/agent_build_prompt_v7.md 与 Docs/architecture/storage_logging_design.md，用 Workflow 工具编排，落地存储&日志重构：先做滚动 .log 文件(单线程 writerScope+单持久 sink+appendBlocking 崩溃同步)，再做 SQLDelight 用户表(先 gradle sync 生成 BlueTraceDb 硬验收，再 schema/仓库/Koin 换绑/删旧 DataStore subjects)。每块 :shared:jvmTest + :app:assembleDebug 绿；最后真机 adb pull logs/ 与 databases/bluetrace.db 取证；直到验收清单(含真机门)全绿；push main。版本/兼容子问题不明先 AskUserQuestion。
```

---

## 二、范围 / 顺序

**IN（本轮）**：严格按 `storage_logging_design.md` 落地两条线。**先日志（低风险、不碰 DB 插件），后 SQLDelight（动 gradle）**。
**OUT**：不做数据迁移（旧 `subjects_json` 弃用、卸载重装空库）；**不**把偏好/会话搬进库；**不**引 FileProvider（复用 `MediaStoreExporter.exportLog`）；不动 UI/VM（接口不变 → 零或极小改动）；不加会话索引表（§5 可选，本轮不做）。

---

## 三、必须落实的审查口径（照设计文档，别退回原始坑）

> 这些是 v8 审查改对的点，实现时**务必照做**，不要按"裸 launch / 每次新 sink"等旧写法回退。

**日志（§1）**
1. **写入串行 = 单线程 writerScope**：注入 `CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))`（Android 侧），持**单个长生命周期 `appendingSink`**、每行 `flush`、跨午夜按 `YYYY-MM-DD` 切 sink。**禁止**用注入的多线程 `Dispatchers.Default` scope 裸 `launch{ 每次新 appendingSink }`（并发追加竞态）。
2. **崩溃同步落盘**：`FileDiagnosticsLog` 暴露实现类专有 `appendBlocking()` + `cancelWriter()`；`BlueTraceApplication` `startKoin` 后 `get<DiagnosticsLog>()` 装 `UncaughtExceptionHandler`，handler 内先 `cancelWriter()` 再 `appendBlocking(ERROR,"crash",stack)`。**不**用异步 `add`。
3. **logsDir = `getExternalFilesDir(null)/"logs"`**（与 `sessionsRoot` 同根、`adb pull` 直接可取），**不**用内部 `filesDir`。
4. **导出复用 `MediaStoreExporter.exportLog`**（已接通），内容源换读当天 `.log`；**不**引 FileProvider。
5. **毫秒**：行格式 `.SSS` 由 `epochMs.mod(1000)` 单独 `pad3`（`epochMsToLocalParts` 只到秒）。
6. **init** 只 `createDirectories`；`pruneOld` 走 `writerScope.launch` 异步（别在主线程/构造期做磁盘遍历删除）。
7. 接口 `entries/add/clear/export` **不变**；保留 `InMemoryDiagnosticsLog` 作测试桩。

**SQLDelight（§2）**
8. **第一硬验收 = `gradle sync` + 生成 `BlueTraceDb` 通过**（项目在 Kotlin 2.2.10 / AGP 9.2.1，SQLDelight `2.0.2` 起步，**不兼容就升 2.1.x**——先验通再往下）。
9. **`io` 调度器注入**：`SqlDelightSubjectRepository(db, io: CoroutineContext)`，Android 注 `Dispatchers.IO`（`Dispatchers.IO` 在 commonMain 不可用，**别**直接写）。
10. **`upsert` 仅"新建才设 current"**：先 `countById(id)` 判新建/编辑，**只有新建**分支 `setMeta("current_subject_id", id)`，编辑老用户**不动 current**（对齐现 DataStore 语义）。
11. **`heightCm` 显式转换**：`row.heightCm?.toInt()`（SQLite INTEGER→`Long?` ↔ `Subject.heightCm:Int?`），upsert 反向 `?.toLong()`。
12. **`__default__` 伪用户永不入 `subject` 表**；`currentId` 存 `app_meta` 字符串；选 Default 走 `setCurrent` 非 `upsert`。
13. 测试驱动 `JdbcSqliteDriver` 现放 `commonTest`（现仓仅 jvm+android 可编过）；注释"将来加 native 须下沉 jvmTest"。

---

## 四、编排（Workflow 模式）

> 共享文件：`AppModule.kt`（两条线都改绑定）、`gradle/libs.versions.toml`、`shared/build.gradle.kts`、`app/build.gradle.kts`。两条线**串行**做（日志→SQLDelight），各线内部可用 agent 并行分析但**写改串行收尾**。

推荐相位（用 `Workflow`）：
- **Phase A «日志».**（串行实现）：FileDiagnosticsLog（§1 全部审查口径）+ Application 崩溃 handler + AppModule 绑定 + SettingsViewModel 导出 + `FileDiagnosticsLogTest` → `:shared:jvmTest` + `:app:assembleDebug` 绿。
- **Phase B «SQLDelight».**（串行实现）：先 gradle 依赖/插件 → **gradle sync + codegen 硬验收** → `Subject.sq` → `SqlDelightSubjectRepository` → AppModule 换绑（DB driver + io）→ 删 `DataStoreSubjectRepository` + 键 + import → `SqlDelightSubjectRepositoryTest` → 单测 + assembleDebug 绿。
- **Phase C «Verify».**（`parallel` 对抗）：(a) 逐条核 §三 审查口径未回退（尤其 #1 单 sink+单线程、#2 appendBlocking、#3 external logsDir、#9 io 注入、#10 upsert current、#11 heightCm）；(b) 无死代码（旧 InMemory 绑定/DataStore subjects 键/未用 import）；(c) `:shared:jvmTest` + `:app:assembleDebug` 复跑绿。汇总必修→修。
- **Phase D «真机».**（串行 · 硬门）：§六，必须 `adb pull` 取证。

---

## 五、不做（OUT）
- 数据迁移 / 旧 subjects_json 兼容；偏好或会话进库；FileProvider；会话索引表；UI 重设计；BLE/DUT 真解码（仍 Mock）。
- 不动 `SessionStore` 文件模型 + manifest、`scenes.json`、`AppPreferences` 偏好部分。

---

## 六、真机自测（硬门 · 必选 · 缺则本轮不算完成）
设备 Xiaomi M2101K9C · Android 13（`adb devices` 取序列号，前轮 `5d510bb4`）。逐条留证据（adb 输出 / 文件片段 / 截图）：

**日志**
1. 跑一次采集/操作 → `adb -s <s> shell run-as`? **不需要**——`adb pull /sdcard/Android/data/io.bluetrace/files/logs/` 取当天 `app-YYYY-MM-DD.log`，确认**有行、格式带毫秒、按天名**。
2. **杀进程再启** → `.log` 仍在且续写（持久）。
3. **触发崩溃**（埋一个测试触发点或 monkey）→ 当天 `.log` 末尾有 `… ERROR [crash] …` 行（证明 `appendBlocking` 同步落盘生效）。
4. 设置E「应用日志」实时显示尾窗；**导出** → `Download/BlueTrace/logs/` 出文件；**清空** → `.log` 删除 + 屏清空。

**SQLDelight**
5. 新建/选择/编辑/删除用户 → `adb pull …/databases/bluetrace.db`，`sqlite3` 或 adb 验 `subject` 行与 `app_meta('current_subject_id')`。
6. **编辑老用户后 current 不变**；**删当前用户后 current 清空**。
7. 卸载重装 → 空库（demo 预期）。

**不回归**：扫描→连接→采集→结束→数据→导出闭环、深色/外观/启动屏正常。

> 真机不可用 → **停下报告**，不得用模拟器或跳过冒充通过。

---

## 七、验收清单（Definition of Done）
1. **日志**：`FileDiagnosticsLog` 按 §三 #1–#7 实现（单线程串行+单 sink+毫秒+external logsDir+MediaStore 导出+异步 prune）；崩溃同步落盘可见；接口未变、消费方零/极小改动。
2. **SQLDelight**：`gradle sync`+codegen 通过；`Subject.sq`+仓库按 §三 #8–#13；`io` 注入、`upsert` current 语义、`heightCm` 转换正确；旧 `DataStoreSubjectRepository`+键+import 删净无死代码。
3. **测试**：`FileDiagnosticsLogTest`（含跨午夜/retain/appendBlocking）+ `SqlDelightSubjectRepositoryTest`（含"编辑不改 current"）绿；`:shared:jvmTest` + `:app:assembleDebug` 全绿。
4. **⛳ 真机门**：§六 全部取证通过。
5. **提交**：每线独立可提交；偏离设计处在提交说明列明；**push main**，commit 末尾 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

---

## 八、构建

```bash
./gradlew :shared:jvmTest          # 日志/用户表 commonTest（含 FakeFileSystem / JdbcSqliteDriver）
./gradlew :app:assembleDebug       # 构建（SQLDelight codegen 在此触发）
./gradlew installDebug             # 装真机自测（§六 必做）
```

> 设计细节一律以 `architecture/storage_logging_design.md`（v8）为准；本 prompt 只是其执行编排 + 审查口径强约束 + 真机门。
