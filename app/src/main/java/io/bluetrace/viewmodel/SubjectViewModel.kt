package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class SubjectUiState(
    val subjects: List<Subject> = emptyList(),
    val currentId: String? = null,
)

/** 用户选择/编辑（用户A/B/C，F-SUBJ-1）。 */
class SubjectViewModel(private val repo: SubjectRepository) : ViewModel() {

    val uiState: StateFlow<SubjectUiState> =
        combine(repo.subjects, repo.currentId) { subjects, currentId ->
            SubjectUiState(subjects, currentId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubjectUiState())

    /** 应用所选用户为当前（含 Default 伪用户 id；v6 改为先选后确认，由屏内确认/返回触发）。 */
    fun select(id: String) {
        viewModelScope.launch { repo.setCurrent(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    /** 保存（新建/编辑）→ 新建即设为当前（DataStore 仓库内处理）。 */
    fun save(subject: Subject) {
        viewModelScope.launch { repo.upsert(subject) }
    }

    suspend fun load(id: String?): Subject? =
        if (id == null) null else repo.subjects.first().firstOrNull { it.id == id }

    fun newId(): String = "subj-" + UUID.randomUUID().toString().take(8)
}
