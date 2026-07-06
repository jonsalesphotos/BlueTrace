package io.bluetrace.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.bluetrace.shared.db.BlueTraceDb
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private const val KEY_CURRENT = "current_subject_id"

/**
 * 用户仓库（v7 · SQLDelight，commonMain，iOS 可复用）。取代 DataStore-JSON 实现。
 * - 用户记录存 `subject` 表；`currentId` 存 `app_meta`（兼容 Default 伪用户 `__default__`，它永不入 subject 表）。
 * - [io] 由各端注入：Android/JVM = `Dispatchers.IO`，未来 iOS = `Dispatchers.Default`（commonMain 不可用 Dispatchers.IO）。
 */
class SqlDelightSubjectRepository(
    private val db: BlueTraceDb,
    private val io: CoroutineContext,
) : SubjectRepository {
    private val q get() = db.subjectQueries

    override val subjects: Flow<List<Subject>> =
        q.selectAll { id, alias, sex, birth, heightCm, weightKg, note ->
            Subject(id, alias, Sex.valueOf(sex), birth, heightCm?.toInt(), weightKg, note)
        }.asFlow().mapToList(io)

    override val currentId: Flow<String?> =
        q.getMeta(KEY_CURRENT).asFlow().mapToOneOrNull(io)

    // 写操作统一切到注入的 io 上下文：SQLite 同步调用不能在调用线程（主线程）直接执行。

    override suspend fun upsert(subject: Subject) = withContext(io) {
        // 仅"新建"才设为当前用户；编辑老用户不动 current（对齐原 DataStoreSubjectRepository 语义）。
        val isNew = q.countById(subject.id).executeAsOne() == 0L
        q.upsert(
            id = subject.id,
            alias = subject.alias,
            sex = subject.sex.name,
            birth = subject.birth,
            heightCm = subject.heightCm?.toLong(), // Int? → INTEGER(Long?)
            weightKg = subject.weightKg,
            note = subject.note,
        )
        if (isNew) q.setMeta(KEY_CURRENT, subject.id)
    }

    override suspend fun delete(id: String) = withContext(io) {
        q.deleteById(id)
        if (q.getMeta(KEY_CURRENT).executeAsOneOrNull() == id) q.deleteMeta(KEY_CURRENT)
    }

    override suspend fun setCurrent(id: String?): Unit = withContext(io) {
        if (id == null) q.deleteMeta(KEY_CURRENT) else q.setMeta(KEY_CURRENT, id)
    }
}
