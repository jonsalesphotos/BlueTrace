package com.example.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetrace.data.android.ExportResult
import com.example.bluetrace.data.android.MediaStoreExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 导出态（导出A 进度 / B 完成 Toast / C 失败，§5.8）。 */
sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class InProgress(val current: String, val progress: Float) : ExportUiState
    data class Done(val displayPath: String) : ExportUiState
    data class Failed(val message: String) : ExportUiState
}

/** 单会话/批量导出（MediaStore → Download/BlueTrace/，§6.4）。 */
class ExportViewModel(private val exporter: MediaStoreExporter) : ViewModel() {
    private val _state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val state: StateFlow<ExportUiState> = _state

    fun export(folderName: String) {
        viewModelScope.launch {
            _state.value = ExportUiState.InProgress(folderName, 0f)
            when (val r = exporter.exportSession(folderName) { p ->
                _state.value = ExportUiState.InProgress(folderName, p)
            }) {
                is ExportResult.Success -> _state.value = ExportUiState.Done(r.displayPath)
                is ExportResult.Error -> _state.value = ExportUiState.Failed(r.message)
            }
        }
    }

    fun exportMany(folders: List<String>) {
        viewModelScope.launch {
            var lastPath = ""
            folders.forEachIndexed { index, folder ->
                _state.value = ExportUiState.InProgress(folder, index.toFloat() / folders.size)
                when (val r = exporter.exportSession(folder) { }) {
                    is ExportResult.Success -> lastPath = r.displayPath
                    is ExportResult.Error -> {
                        _state.value = ExportUiState.Failed(r.message)
                        return@launch
                    }
                }
            }
            _state.value = ExportUiState.Done(if (folders.size == 1) lastPath else "Download/BlueTrace/")
        }
    }

    fun reset() { _state.value = ExportUiState.Idle }
}
