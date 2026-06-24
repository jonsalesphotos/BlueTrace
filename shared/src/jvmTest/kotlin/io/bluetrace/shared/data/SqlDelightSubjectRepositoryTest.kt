package io.bluetrace.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.bluetrace.shared.db.BlueTraceDb
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SQLDelight 用户仓库（v7）：内存 JDBC 驱动，验证 current 语义/类型映射/删除联动。
 * 重点回归口径：编辑老用户**不改** current（#10）、heightCm Int?↔INTEGER Long? 往返（#11）、删当前清 current。
 */
class SqlDelightSubjectRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SqlDelightSubjectRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BlueTraceDb.Schema.create(driver)
        repo = SqlDelightSubjectRepository(BlueTraceDb(driver), Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() = driver.close()

    private fun subj(id: String, alias: String = id, h: Int? = null, w: Double? = null) =
        Subject(id, alias, Sex.FEMALE, "2000-01", h, w, null)

    @Test
    fun upsertNew_insertsAndBecomesCurrent() = runTest {
        repo.upsert(subj("u1", "Alice"))
        assertEquals(listOf("u1"), repo.subjects.first().map { it.id })
        assertEquals("u1", repo.currentId.first())
    }

    @Test
    fun upsertSecondNew_movesCurrentToNewest() = runTest {
        repo.upsert(subj("u1"))
        repo.upsert(subj("u2"))
        assertEquals("u2", repo.currentId.first())
        assertEquals(listOf("u1", "u2"), repo.subjects.first().map { it.id }) // ORDER BY alias
    }

    @Test
    fun editExisting_doesNotChangeCurrent() = runTest {
        repo.upsert(subj("u1"))
        repo.upsert(subj("u2")) // current = u2
        repo.upsert(subj("u1", alias = "AliceEdited", h = 170)) // 编辑老用户
        assertEquals("u2", repo.currentId.first()) // current 不动
        val u1 = repo.subjects.first().first { it.id == "u1" }
        assertEquals("AliceEdited", u1.alias)
        assertEquals(170, u1.heightCm) // 编辑生效
    }

    @Test
    fun heightWeight_roundTripWithNulls() = runTest {
        repo.upsert(subj("u1", h = 165, w = 55.5))
        repo.upsert(subj("u2", h = null, w = null))
        val map = repo.subjects.first().associateBy { it.id }
        assertEquals(165, map["u1"]!!.heightCm)
        assertEquals(55.5, map["u1"]!!.weightKg)
        assertNull(map["u2"]!!.heightCm)
        assertNull(map["u2"]!!.weightKg)
    }

    @Test
    fun deleteCurrent_clearsCurrent() = runTest {
        repo.upsert(subj("u1"))
        repo.delete("u1")
        assertEquals(emptyList(), repo.subjects.first())
        assertNull(repo.currentId.first())
    }

    @Test
    fun deleteNonCurrent_keepsCurrent() = runTest {
        repo.upsert(subj("u1"))
        repo.upsert(subj("u2")) // current = u2
        repo.delete("u1")
        assertEquals("u2", repo.currentId.first())
    }

    @Test
    fun setCurrent_setsAndClears() = runTest {
        repo.upsert(subj("u1"))
        repo.upsert(subj("u2"))
        repo.setCurrent("u1")
        assertEquals("u1", repo.currentId.first())
        // Default 伪用户 id 也可存（不入 subject 表）
        repo.setCurrent("__default__")
        assertEquals("__default__", repo.currentId.first())
        repo.setCurrent(null)
        assertNull(repo.currentId.first())
    }
}
