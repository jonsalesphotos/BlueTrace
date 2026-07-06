package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.domain.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DataUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val totalCount: Int = 0,
    val totalBytes: Long = 0,
    val query: String = "",
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
)

/** 数据 Tab（数据A/B/C/D）：会话列表 + 搜索 + 多选 + 删除（模式筛选已移除，后期再做）。 */
class DataViewModel(private val store: SessionStore) : ViewModel() {

    private val _all = MutableStateFlow<List<SessionSummary>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _selectionMode = MutableStateFlow(false)
    private val _selected = MutableStateFlow<Set<String>>(emptySet())

    private data class Filters(val query: String, val selMode: Boolean, val selected: Set<String>)

    val uiState: StateFlow<DataUiState> =
        combine(
            _all,
            combine(_query, _selectionMode, _selected) { q, s, sel -> Filters(q, s, sel) },
        ) { all, filters ->
            val filtered = all
                .filter {
                    filters.query.isBlank() ||
                        it.folderName.contains(filters.query, true) ||
                        it.subjectAlias.contains(filters.query, true)
                }
            DataUiState(
                sessions = filtered,
                totalCount = all.size,
                totalBytes = all.sumOf { it.totalBytes },
                query = filters.query,
                selectionMode = filters.selMode,
                selected = filters.selected,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            _all.value = list
        }
    }

    fun setQuery(q: String) { _query.value = q }

    /** 进入多选：顶栏「选择」不带参 = 空选（不暗中预选任何会话）；长按某条 = 预选该条。 */
    fun enterSelection(folder: String? = null) {
        _selectionMode.value = true
        _selected.value = if (folder == null) emptySet() else setOf(folder)
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(folder: String) {
        _selected.value = _selected.value.let { if (folder in it) it - folder else it + folder }
    }

    fun selectAll() {
        _selected.value = uiState.value.sessions.map { it.folderName }.toSet()
    }

    fun deleteSelected() {
        val targets = _selected.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) { targets.forEach { store.delete(it) } }
            exitSelection()
            refresh()
        }
    }
}
