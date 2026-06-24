package io.bluetrace.data.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.bluetrace.shared.data.BlueTraceJson
import io.bluetrace.shared.domain.AppLanguage
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bluetrace")

private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch_completed")
private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_LANGUAGE = stringPreferencesKey("app_language")
private val KEY_SCENE_MAIN = stringPreferencesKey("scene_main")
private val KEY_SCENE_SUB = stringPreferencesKey("scene_sub")
private val KEY_SUBJECTS = stringPreferencesKey("subjects_json")
private val KEY_CURRENT_SUBJECT = stringPreferencesKey("current_subject_id")

/** 首启标记 + GNSS 偏好（DataStore，§7.2 ⑤）。 */
class DataStoreAppPreferences(private val context: Context) : AppPreferences {
    override val firstLaunchCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FIRST_LAUNCH] ?: false }

    override suspend fun setFirstLaunchCompleted(value: Boolean) {
        context.dataStore.edit { it[KEY_FIRST_LAUNCH] = value }
    }

    override val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { prefs ->
            runCatching { ThemeMode.valueOf(prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM)
        }

    override suspend fun setThemeMode(value: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = value.name }
    }

    // 语言：默认中文（无"跟随系统"）。本轮持久化偏好 + 选中态；运行时 locale 切换属后续轮。
    override val language: Flow<AppLanguage> =
        context.dataStore.data.map { prefs ->
            runCatching { AppLanguage.valueOf(prefs[KEY_LANGUAGE] ?: AppLanguage.ZH.name) }
                .getOrDefault(AppLanguage.ZH)
        }

    override suspend fun setLanguage(value: AppLanguage) {
        context.dataStore.edit { it[KEY_LANGUAGE] = value.name }
    }

    // 采集场景：持久化上次选择（main/sub 两键）；未选过返回 null → 上层用 scenes.json 第一项。
    override val sceneSelection: Flow<SceneSelection?> =
        context.dataStore.data.map { p ->
            val m = p[KEY_SCENE_MAIN]; val s = p[KEY_SCENE_SUB]
            if (m != null && s != null) SceneSelection(m, s) else null
        }

    override suspend fun setSceneSelection(value: SceneSelection) {
        context.dataStore.edit { it[KEY_SCENE_MAIN] = value.mainToken; it[KEY_SCENE_SUB] = value.subToken }
    }
}

/** 用户本地 CRUD（DataStore，JSON 列表 + 当前 id；F-SUBJ-1）。 */
class DataStoreSubjectRepository(private val context: Context) : SubjectRepository {
    override val subjects: Flow<List<Subject>> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_SUBJECTS]?.let {
                runCatching { BlueTraceJson.decodeFromString<List<Subject>>(it) }.getOrNull()
            } ?: emptyList()
        }

    override val currentId: Flow<String?> =
        context.dataStore.data.map { it[KEY_CURRENT_SUBJECT] }

    override suspend fun upsert(subject: Subject) {
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_SUBJECTS]?.let {
                runCatching { BlueTraceJson.decodeFromString<List<Subject>>(it) }.getOrNull()
            } ?: emptyList()
            val next = if (cur.any { it.id == subject.id }) {
                cur.map { if (it.id == subject.id) subject else it }
            } else {
                cur + subject
            }
            prefs[KEY_SUBJECTS] = BlueTraceJson.encodeToString(next)
            // 新建即设为当前
            if (cur.none { it.id == subject.id }) prefs[KEY_CURRENT_SUBJECT] = subject.id
        }
    }

    override suspend fun delete(id: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_SUBJECTS]?.let {
                runCatching { BlueTraceJson.decodeFromString<List<Subject>>(it) }.getOrNull()
            } ?: emptyList()
            prefs[KEY_SUBJECTS] = BlueTraceJson.encodeToString(cur.filterNot { it.id == id })
            if (prefs[KEY_CURRENT_SUBJECT] == id) prefs.remove(KEY_CURRENT_SUBJECT)
        }
    }

    override suspend fun setCurrent(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_CURRENT_SUBJECT) else prefs[KEY_CURRENT_SUBJECT] = id
        }
    }
}
