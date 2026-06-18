package com.example.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetrace.shared.data.SessionStore
import com.example.bluetrace.shared.domain.SessionSummary
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
            val s = withContext(Dispatchers.IO) { store.detail(folderName) }
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

    val folder: String get() = folderName
}
