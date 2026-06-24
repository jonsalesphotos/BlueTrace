# BlueTrace 存储 & 日志 重构设计方案（v8 · SQLite(SQLDelight) + .log 文件 · 多视角审查修订）

> 目标：把"用户"从 DataStore-JSON 换成 **SQLite（SQLDelight，KMP 原生）**；把应用日志从内存换成**滚动 .log 文件**。
> 阶段：**demo**，**直接替换、不做数据迁移**（旧 `subjects_json` 直接弃用）。
> 权威约束沿用：shared 为 KMP（commonMain 接口 + 平台实现，未来 iOS 复用）；偏好留 DataStore；会话原始数据留文件。
>
> **v8 审查修订（基于真实代码取证，verdict = approve-with-changes）**：① 日志写入从"裸 `scope.launch` + 每条 add 现开现关 sink"改为**单持久 `appendingSink` + 单线程串行**（修真并发追加竞态——注入的 scope 是 `Dispatchers.Default` 多线程池，非串行）；② 崩溃日志加**同步落盘入口 `appendBlocking`**（异步 add 在进程被杀前刷不到盘）；③ `logsDir` 改 **`getExternalFilesDir(null)/"logs"`**（与会话同根、`adb pull` 直接可取；内部 `filesDir` 非 root 拉不出）；④ 导出**复用已存在的 `MediaStoreExporter.exportLog`**（删 FileProvider 建议）；⑤ commonMain 的 `io` 调度器来源**写死=注入**（`Dispatchers.IO` 在 commonMain 不可用）；⑥ 毫秒由 `epochMs % 1000` 单独取（`epochMsToLocalParts` 只到秒）；⑦ `upsert` **仅"新建才设 current"**（`INSERT OR REPLACE` 区分不了新建/编辑）；⑧ `heightCm` `Int?`↔`Long?` 显式转换；⑨ `init` 的 `pruneOld` 改异步（避免主线程磁盘 IO）；⑩ 补回滚 / 可验证验收 / 生产前 migration 说明。

---

## 0. 范围与边界（先划清"换/不换"）

| 数据 | 现状 | 决策 | 理由 |
|---|---|---|---|
| 用户 Subject（含 currentId） | DataStore `subjects_json` 一段 JSON | **→ SQLite（SQLDelight）** | 记录集合，需查询/排序/单条增删，blob 不专业 |
| 偏好（主题/语言/采集场景/首启） | DataStore Preferences | **保持 DataStore（不动）** | 少量标量键值，DataStore 是 Android 现代标准；进库是倒退 |
| 会话原始数据（raw HEX / CSV） | 磁盘文件夹 | **保持文件（不动）** | source-of-truth、要导出/重放，是产品本身 |
| 会话 `session_manifest.json` | JSON sidecar | **保持 JSON（不动）** | 自描述、跨端可读 |
| 会话列表/检索 | 扫文件夹 + 解析全部 manifest | **暂不动**；§5 列为可选索引 | 当前量级够用；二期上传再加索引表 |
| 场景词表 scenes.json | assets/JSON | **保持（不动）** | 可热改、无需代码 |
| 应用日志 DiagnosticsLog | 内存环形缓冲（断电即失） | **→ 滚动 .log 文件** + 内存尾窗 | 持久、可排错、可导出 |

非目标：不引入 ORM 重框架；不把偏好/会话搬进库；不为 iOS 现在配 target（仅保证选型不挡路）。
> 触发条件透明化：SQLite 主要为 **iOS 复用 + 二期上传索引**提前铺路，非当前 7 字段小表的功能必需——这是有意的前瞻取舍，可接受按本方案推进。

---

## 1. 日志 → 滚动 .log 文件

### 1.1 现状
`shared/.../session/DiagnosticsLog.kt`：`interface DiagnosticsLog { entries: StateFlow<List<DiagnosticEntry>>; add(); clear(); export():String }`；
实现 `InMemoryDiagnosticsLog`（capacity 500，纯内存）。消费方：`DefaultSessionController.diagnostics.add(...)`、`SettingsViewModel.{logEntries,exportLog,clearLog}` → `AppLogScreen`（设置E）。

### 1.2 目标
- 每条事件**追加写入 .log 文件**，跨进程/重启持久。
- 按天分文件：`logs/app-YYYY-MM-DD.log`（**带连字符**）；启动时**异步**清理 N 天前（默认保留 7 天）。
- 保留**内存尾窗**（最近 ~500 条）供「应用日志」屏实时显示（UI 不读磁盘）。
- 导出 = **复用现有 `MediaStoreExporter.exportLog(content, fileName)`**（写公共 `Download/BlueTrace/logs/`、经 MediaStore、免权限、minSdk29 OK，`SettingsViewModel.exportLog` 已接通）：把内容源换成读当天（或全部）`.log`，文件名/MIME 相应调整。清空 = 删 .log 文件 + 清内存。**不引 FileProvider**（当前 manifest 无 `<provider>`，MediaStore 已够）。
- 行格式（人读）：`2026-06-24 14:00:39.123 WARN [ble] message`（本地时区）。年月日时分秒用 `epochMsToLocalParts`；**毫秒 = `epochMs.mod(1000)` 单独 `pad3`** 拼在秒后（`epochMsToLocalParts` 内部已 `floorDiv(1000)` 丢毫秒、`LocalDateTimeParts` 无毫秒字段——不能声称"直接用现有函数"产毫秒）。

### 1.3 设计（shared commonMain · okio FileSystem · 单持久 sink + 单线程串行）
> ⚠️ **并发取证**：app 注入的 `single<CoroutineScope>{ CoroutineScope(SupervisorJob()+Dispatchers.Default) }`（`AppModule.kt:48`）是**多线程池**。若按"每条 `add` 各起 `scope.launch{ appendingSink(path).buffer() … }`"——多条 add **无 happens-before、并发追加同一文件 → 行交错/丢行/时间戳乱序**。这与 `SessionController` 无竞态的原因相反：它走**单 Channel-actor + 单个长生命周期 `BufferedSink`（`RawHexWriter`）行级 flush**。故本设计**照搬"单持久 sink + 串行"**，不用裸 launch。

实现 `FileDiagnosticsLog : DiagnosticsLog`：
```kotlin
class FileDiagnosticsLog(
    private val fileSystem: FileSystem,
    private val logsDir: Path,            // 注入：Android = getExternalFilesDir(null)/"logs"
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val writerScope: CoroutineScope, // 注入：单线程！Android = SupervisorJob()+Dispatchers.IO.limitedParallelism(1)
    private val capacity: Int = 500,
    private val retainDays: Int = 7,
) : DiagnosticsLog {
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    override val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()  // 内存尾窗(UI 用)

    private var sink: BufferedSink? = null   // 单个长生命周期 sink
    private var sinkDay: String? = null      // 当前 sink 所属 "YYYY-MM-DD"

    init { fileSystem.createDirectories(logsDir); writerScope.launch { pruneOld() } }  // 构造期只建目录；prune 异步

    override fun add(level: LogLevel, tag: String, message: String) {
        val e = DiagnosticEntry(clock.nowMs(), level, tag, message)
        _entries.update { (it + e).takeLast(capacity) }      // 尾窗：StateFlow.update 原子，线程安全
        writerScope.launch { appendLocked(e) }               // 文件：单线程 scope 串行（保序）
    }
    override fun clear() { _entries.value = emptyList(); writerScope.launch { closeSink(); deleteAllLogs() } }
    override fun export(): String = readCurrentOrAll()        // 供 MediaStoreExporter.exportLog 的内容源

    /** 崩溃专用（实现类专有，非接口）：调用线程内同步落一条并 flush。崩溃 handler 先 cancel writerScope 再调它，避免与异步写争同一 sink。 */
    fun appendBlocking(level: LogLevel, tag: String, message: String) =
        appendLocked(DiagnosticEntry(clock.nowMs(), level, tag, message))

    private fun appendLocked(e: DiagnosticEntry) {           // 仅在 writerScope 单线程 或 cancel 后的崩溃线程调用
        val day = dayOf(e.epochMs)                            // "YYYY-MM-DD"（用 parts.year/month/day 手拼连字符）
        if (day != sinkDay) { closeSink(); sink = fileSystem.appendingSink(logsDir / "app-$day.log").buffer(); sinkDay = day }
        sink!!.writeUtf8(format(e)).writeUtf8("\n").flush()   // 行级 flush，durable
    }
    private fun closeSink() { sink?.close(); sink = null; sinkDay = null }
    // format(e): "${parts(e).y-M-d} ${parts.H:m:s}.${pad3(e.epochMs.mod(1000))} ${e.level} [${e.tag}] ${e.message}"
}
```
- **接口不变**（`entries/add/clear/export` 全保留）→ `SettingsViewModel`/`AppLogScreen` 几乎不动。`appendBlocking` 为**实现类专有**（不进接口），仅崩溃 handler 用。
- **串行 = 单线程 writerScope**（`Dispatchers.IO.limitedParallelism(1)`，平台侧注入）：所有 `add` 的 `launch` 跑在唯一线程 → 写入串行且保序，**无并发追加竞态**。持**单个长生命周期 `appendingSink`**、每行 `flush`。（备选：用 `Mutex` 串行——但单线程 scope 更直白且与 `RawHexWriter` 同思路。）
- **logsDir**：`getExternalFilesDir(null)/"logs"`（与 `sessionsRoot` 同根；免权限、minSdk29 OK、**`adb pull` 直接可取**）。内部 `filesDir` 非 root 拉不出，故不放那。
- **跨午夜滚动**：`appendLocked` 每次比对 `day`，不同则 `flush+close` 旧 sink、`open` 新 sink。文件名连字符 `YYYY-MM-DD`（与 `dateCompact()` 的无连字符 `yyyyMMdd` 不同，需 `parts.year/month/day` 手拼）。
- **init**：构造期只 `createDirectories`（轻）；`pruneOld`（列目录+按日期删 N 天前，一组磁盘 IO）移到 `writerScope.launch` 异步——避免 Koin `single` 解析（很可能主线程）触 StrictMode + 冷启动卡顿。

### 1.4 崩溃日志（建议同批做）
```kotlin
// BlueTraceApplication.onCreate：startKoin 之后，拿到实现再装 handler
val diagnostics = get<DiagnosticsLog>()                       // Koin 已 start
val prev = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { t, e ->
    (diagnostics as? FileDiagnosticsLog)?.let {
        it.cancelWriter()                                     // 先停异步写，避免与同步写争 sink
        it.appendBlocking(LogLevel.ERROR, "crash", e.stackTraceToString())  // 同步落盘 + flush
    }
    prev?.uncaughtException(t, e)
}
```
- 崩溃时**必须同步落盘**：用 `appendBlocking`（当前线程 write+flush），**不**用异步 `add`（`Dispatchers.Default` 池里 pending 写会随进程被杀丢失——最该留的那条没了）。`cancelWriter()`（cancel `writerScope` 的 children）先停异步路径，`appendBlocking` 再独占 sink 写一条。`BlueTraceApplication.onCreate` 当前只 `startKoin`，须补 `get<DiagnosticsLog>()` 取实现再装 handler。

### 1.5 改动清单（日志）
- 改 `shared/.../session/DiagnosticsLog.kt`：新增 `FileDiagnosticsLog`（接口不变 + 实现类专有 `appendBlocking`/`cancelWriter`；保留 `InMemoryDiagnosticsLog` 作测试桩）。
- `di/AppModule.kt`：① `single<DiagnosticsLog> { FileDiagnosticsLog(get(), logsDir(androidContext()), get(), get(), get(named("logWriter"))) }`；② 提供 `logsDir = getExternalFilesDir(null)/"logs"`（与 `sessionsRoot` 同根）；③ 提供单线程 `single(named("logWriter")) { CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1)) }`；④ **删**旧 `single<DiagnosticsLog>{ InMemoryDiagnosticsLog(...) }` 绑定 + 其 import。
- `BlueTraceApplication`：`startKoin` 后 `get<DiagnosticsLog>()` 装崩溃 handler（`cancelWriter`+`appendBlocking`）。
- `SettingsViewModel.exportLog`：**复用 `MediaStoreExporter.exportLog`**（已接通），内容源换成读当天 `.log`；**不引 FileProvider**。
- 测试：`FileDiagnosticsLogTest`（commonTest，`okio-fakefilesystem` 已在）——add→文件含行、按天名（连字符）、**跨午夜切文件（可控时钟）**、clear 删文件、retain 裁剪、`appendBlocking` 同步落一条。

---

## 2. 用户 → SQLite（SQLDelight）

### 2.1 选型：SQLDelight（不是 Room）
- shared 是 **KMP**（未来 iOS）。SQLDelight 是 KMP 原生、生成类型安全查询、各平台 driver 齐全（Android / Native / JVM(测试)）。
- Room-KMP 虽有，但 Android 味重、KMP 成熟度不及 SQLDelight。
- 数据层（`SubjectRepository` 实现）可放 **shared commonMain**，iOS 直接复用，只需各端注入 driver。

### 2.2 gradle / 版本目录
> ⚠️ **工具链兼容（硬验收前置）**：项目在 **Kotlin 2.2.10 / AGP 9.2.1**（`libs.versions.toml:2-3`，偏新）。SQLDelight 2.0.2(2024) 对 Kotlin 2.2 / AGP 9 的官方兼容未必覆盖。**落地 SQL 阶段第一硬验收 = `gradle sync` + 代码生成 `BlueTraceDb` 通过**；不通过则升 SQLDelight 到 2.0.x 最新补丁或 **2.1.x**。下面 `2.0.2` 为起点版本，按兼容性可上调。

`gradle/libs.versions.toml`：
```toml
[versions]
sqldelight = "2.0.2"   # 若与 Kotlin 2.2.10/AGP 9.2.1 不兼容则升 2.1.x
[libraries]
sqldelight-runtime            = { group = "app.cash.sqldelight", name = "runtime", version.ref = "sqldelight" }
sqldelight-coroutines-ext     = { group = "app.cash.sqldelight", name = "coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android-driver     = { group = "app.cash.sqldelight", name = "android-driver", version.ref = "sqldelight" }
sqldelight-sqlite-driver      = { group = "app.cash.sqldelight", name = "sqlite-driver", version.ref = "sqldelight" } # jvmTest
[plugins]
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```
`shared/build.gradle.kts`：
```kotlin
plugins { ...; alias(libs.plugins.sqldelight) }
sqldelight { databases { create("BlueTraceDb") { packageName.set("io.bluetrace.shared.db") } } }
kotlin {
  sourceSets {
    commonMain.dependencies { implementation(libs.sqldelight.runtime); implementation(libs.sqldelight.coroutines.ext) }
    commonTest.dependencies { implementation(libs.sqldelight.sqlite.driver) }   // JdbcSqliteDriver(IN_MEMORY)
  }
}
```
`app/build.gradle.kts`：`implementation(libs.sqldelight.android.driver)`。
> **测试源集取舍**：现仓 `shared` 仅 `jvm()+android()`（无 native），把 JVM-only 的 `JdbcSqliteDriver` 放 `commonTest` **当前可编过**；但本方案卖点是"commonMain 数据层 iOS 复用"，**将来真加 iOS native target 时须把测试驱动下沉到 `jvmTest` 专属源集**（否则 native 编不过）。区分："测试代码位置(commonTest)" ≠ "门禁任务(`:shared:jvmTest`)"。

### 2.3 Schema（`shared/src/commonMain/sqldelight/io/bluetrace/shared/db/Subject.sq`）
```sql
CREATE TABLE subject (
  id       TEXT NOT NULL PRIMARY KEY,
  alias    TEXT NOT NULL,
  sex      TEXT NOT NULL,           -- Sex.name: MALE/FEMALE/OTHER
  birth    TEXT NOT NULL DEFAULT '',
  heightCm INTEGER,                 -- 可空；SQLDelight 生成 Long?，映射 Subject.heightCm:Int? 需显式转换
  weightKg REAL,                    -- 可空；→ Double? 直配
  note     TEXT
);
CREATE TABLE app_meta ( key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL );  -- 存 currentId 等

selectAll:  SELECT * FROM subject ORDER BY alias;
countById:  SELECT COUNT(*) FROM subject WHERE id = ?;   -- upsert 判新建/编辑用
upsert:     INSERT OR REPLACE INTO subject(id,alias,sex,birth,heightCm,weightKg,note) VALUES (?,?,?,?,?,?,?);
deleteById: DELETE FROM subject WHERE id = ?;
getMeta:    SELECT value FROM app_meta WHERE key = ?;
setMeta:    INSERT OR REPLACE INTO app_meta(key,value) VALUES (?,?);
deleteMeta: DELETE FROM app_meta WHERE key = ?;
```
> `currentId` 存 `app_meta('current_subject_id', ...)` —— 因为当前用户可能是 **Default 伪用户**（`__default__`，**永不写入 subject 表**），不能用外键/标志列，存字符串最稳。UI 选 Default 走 `setCurrent` 而非 `upsert`。

### 2.4 仓库实现（shared commonMain，接口不变）
`SqlDelightSubjectRepository(private val db: BlueTraceDb, private val io: CoroutineContext) : SubjectRepository`：
> **`io` 来源（必须写死）**：`mapToList(io)/mapToOneOrNull(io)` 需 `CoroutineContext`，而 **`Dispatchers.IO` 在 commonMain 不可用**（仅 JVM/Android），现有 DI 也没注入 IO 维度。**取注入 `CoroutineContext`**：Android/JVM 注 `Dispatchers.IO`，未来 iOS 注 `Dispatchers.Default`。（也可 commonMain 内直接用 `Dispatchers.Default` 占位——查询在 driver 上同步执行，该 context 主要决定下游 `map` 在哪跑。）
- `subjects: Flow<List<Subject>>` = `db.subjectQueries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }`。
- `currentId: Flow<String?>` = `db.appMetaQueries.getMeta("current_subject_id").asFlow().mapToOneOrNull(io).map { it?.value }`。
- `upsert(s)`：`subjectQueries.upsert(...)`；**仅新建才设当前**——先 `countById(s.id)` 判断是否已存在，**只有新建分支** `setMeta("current_subject_id", s.id)`，**编辑老用户不动 current**（对齐现 `DataStoreSubjectRepository.upsert`，`PreferencesDataStore.kt:89-96` 语义）。
- `delete(id)`：`deleteById(id)`；若删的是当前 → `deleteMeta`。
- `setCurrent(id)`：`id==null ? deleteMeta : setMeta(...)`。
- `toDomain()`：`sex = Sex.valueOf(row.sex)`；**`heightCm = row.heightCm?.toInt()`**（`INTEGER→Long?` 而 `Subject.heightCm:Int?`）；`weightKg = row.weightKg`（`REAL→Double?` 直配）。`upsert` 绑定反向 `heightCm?.toLong()`。

### 2.5 Driver 注入（各端提供，shared 不碰平台）
`app/.../di/AppModule.kt`：
```kotlin
single<SqlDriver> { AndroidSqliteDriver(BlueTraceDb.Schema, androidContext(), "bluetrace.db") }
single { BlueTraceDb(get()) }
single<SubjectRepository> { SqlDelightSubjectRepository(get(), Dispatchers.IO) }   // io = Dispatchers.IO（Android）
// 删 single<SubjectRepository>{ DataStoreSubjectRepository(...) } + 其 import
```
（未来 iOS：`NativeSqliteDriver(BlueTraceDb.Schema, "bluetrace.db")` + `io = Dispatchers.Default`。）

### 2.6 demo 直接替换（无迁移）
- **删** `DataStoreSubjectRepository`（PreferencesDataStore.kt 内）+ `KEY_SUBJECTS`/`KEY_CURRENT_SUBJECT`。
- `AppPreferences`/DataStore **只留**首启/主题/语言/场景（subjects 不再走 DataStore）。
- 卸载重装即空库（demo，可接受）。

### 2.7 测试
`SqlDelightSubjectRepositoryTest`（commonTest）：`JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { BlueTraceDb.Schema.create(it) }` + `io = Dispatchers.Default`（或 `Unconfined`）→ upsert/select/delete/current 流，**含"编辑老用户不改 current"**用例；进现有 `:shared:jvmTest` 门禁。

---

## 3. 改动清单汇总

**新增**
- `shared/.../sqldelight/io/bluetrace/shared/db/Subject.sq`（schema + 查询）
- `shared/.../domain/SqlDelightSubjectRepository.kt`
- `shared/.../session/FileDiagnosticsLog.kt`（或并入 DiagnosticsLog.kt）
- 测试：`SqlDelightSubjectRepositoryTest.kt`、`FileDiagnosticsLogTest.kt`

**修改**
- `gradle/libs.versions.toml`、`shared/build.gradle.kts`、`app/build.gradle.kts`（SQLDelight 依赖/插件）
- `app/.../di/AppModule.kt`：删 `import ...DataStoreSubjectRepository`、删 `import ...InMemoryDiagnosticsLog`、换 `single<DiagnosticsLog>` 绑定（FileDiagnosticsLog + logsDir + 单线程 writerScope）、换 `single<SubjectRepository>` 绑定（SqlDelight + DB driver + io）
- `app/.../data/android/PreferencesDataStore.kt`（删 `DataStoreSubjectRepository` + `KEY_SUBJECTS`/`KEY_CURRENT_SUBJECT`）
- `BlueTraceApplication`（崩溃 handler：`get<DiagnosticsLog>()` + `cancelWriter`+`appendBlocking`）
- `app/.../viewmodel/SettingsViewModel.kt`（导出复用 `MediaStoreExporter.exportLog`，内容源换 `.log`）

**不动**（明确）
- `AppPreferences` 偏好部分、`SessionStore` 文件模型 + manifest、`scenes.json`、所有屏 UI/VM（接口不变 → 零或极小改动）；**不引 FileProvider**（MediaStore 已够）

---

## 4. 落地顺序与验收
1. **日志 .log**（独立、低风险、不碰 DB 插件）→ `:app:assembleDebug` + `:shared:jvmTest` 绿 → **真机**：`getExternalFilesDir/logs/app-*.log` 生成、设置E 实时显示、导出（MediaStore→`Download/BlueTrace/logs/`）/清空生效；**杀进程后重启 `.log` 仍在**、**触发 crash handler 后当天 `.log` 末尾有 `crash` 行**、**retain 裁剪生效**、**跨午夜切文件**（单测覆盖）。接入受阻 → `git revert` 本步。
2. **SQLDelight 用户表**：**先 `gradle sync` + 生成 `BlueTraceDb` 通过（硬验收，工具链兼容性）** → schema → 仓库 → Koin 换绑 → 删旧 DataStore subjects → 单测绿 → **真机**：新建/选择/编辑/删除用户落库（`adb pull` `databases/bluetrace.db` 验 `subject`/`app_meta` 表）、**编辑老用户不改 current**、删当前用户清 current。接入受阻 → `git revert`（DataStore 实现仍在历史可恢复）。
3. （可选）会话列表 SQLite 索引（性能）。

每步独立可提交；commit 末尾沿用 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

## 5. 风险 / 取舍
- **日志写入**：用**单线程 writerScope（`limitedParallelism(1)`）+ 单持久 `appendingSink` + 行级 flush**（非裸 launch+每次新 sink），保序无竞态；崩溃走 `appendBlocking` 同步落盘（先 `cancelWriter`）。
- **SQLDelight 版本兼容**：插件引入需 `gradle sync` + 首次代码生成；**须对 Kotlin 2.2.10 / AGP 9.2.1 验通**（§2.2 第一硬验收），必要时升 2.1.x。
- `currentId` 用 meta 表字符串（兼容 `__default__` 伪用户、永不入表）——非外键，靠应用层一致。
- **demo 卸载重装空库**可接受；**脱离 demo 前须补 `.sqm` migration + schema version**，否则首个生产 schema 变更即崩（`AndroidSqliteDriver` version 不匹配会抛）。
- `commonMain` 的 `io` 调度器**由各端注入**（`Dispatchers.IO` 不在 commonMain）。
- 偏好/会话**坚持不进库**——避免"为专业而专业"的过度工程；库只承载真正的关系型数据（用户，及后续会话索引/上传状态）。
