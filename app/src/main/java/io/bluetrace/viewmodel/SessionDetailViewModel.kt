package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.SessionSummary
import io.bluetrace.shared.domain.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 会话详情（数据C）：manifest 摘要 + 文件夹构成逐项可选（导出仍整夹，v1 简化）。 */
class SessionDetailViewModel(
    private val folderName: String,
    private val store: SessionStore,
) : ViewModel() {

    private val _summary = MutableStateFlow<SessionSummary?>(null)
    val summary: StateFlow<SessionSummary?> = _summary

    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles

    init { load() }

    fun load() {
        viewModelScope.launch {
            val s = store.detail(folderName) // Store 自守 IO（A3）
            _summary.value = s
            _selectedFiles.value = s?.files?.map { it.relativePath }?.toSet() ?: emptySet()
        }
    }

    fun toggleFile(path: String) {
        _selectedFiles.value = _selectedFiles.value.let { if (path in it) it - path else it + path }
    }

    fun selectAllFiles() {
        _selectedFiles.value = _summary.value?.files?.map { it.relativePath }?.toSet() ?: emptySet()
    }

    /** 事后改采集人/场景（数据C）：重写 manifest + 重命名文件夹，回调新摘要（null=冲突）。 */
    fun editTo(subject: Subject, scene: SceneSelection, onResult: (SessionSummary?) -> Unit) {
        viewModelScope.launch {
            val res = store.editSession(folderName, subject.alias, subject.sex, subject.birth, subject.heightCm, subject.weightKg, scene)
            onResult(res)
        }
    }

    val folder: String get() = folderName
}
