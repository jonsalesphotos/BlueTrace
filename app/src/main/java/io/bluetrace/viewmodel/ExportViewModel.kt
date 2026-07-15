package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import io.bluetrace.data.android.ExportResult
import io.bluetrace.data.android.MediaStoreExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 导出态(导出A 进度 / B 完成 Toast / C 失败 / D 存储不足, §5.8).  */
sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class InProgress(val current: String, val progress: Float) : ExportUiState
    data class Done(val displayPath: String) : ExportUiState
    data class Failed(val message: String) : ExportUiState
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : ExportUiState
}

/**
 * 单会话/批量导出(MediaStore → Download/BlueTrace/, §6.4).
 * 导出任务跑在**应用级 scope**: 页面销毁不再把 zip 写一半就取消(曾留下 IS_PENDING 幽灵文件);
 * 取消只经 [cancel](exporter 会清掉 pending 记录).
 */
class ExportViewModel(
    private val exporter: MediaStoreExporter,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val state: StateFlow<ExportUiState> = _state
    private var job: Job? = null

    /** @param selectedFiles null=整夹; 非空=仅导出所选相对路径(数据C 勾选导出).  */
    fun export(folderName: String, selectedFiles: Set<String>? = null) {
        if (_state.value is ExportUiState.InProgress) return // 防重入
        job = appScope.launch {
            _state.value = ExportUiState.InProgress(folderName, 0f)
            when (val r = exporter.exportSession(folderName, selectedFiles) { p ->
                _state.value = ExportUiState.InProgress(folderName, p)
            }) {
                is ExportResult.Success -> _state.value = ExportUiState.Done(r.displayPath)
                is ExportResult.Error -> _state.value = ExportUiState.Failed(r.message)
                is ExportResult.InsufficientSpace -> _state.value = ExportUiState.InsufficientSpace(r.requiredBytes, r.availableBytes)
            }
        }
    }

    /** 取消当前导出: exporter 在取消路径上删除 IS_PENDING 记录, 不留幽灵文件.  */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ExportUiState.Idle
    }

    fun exportMany(folders: List<String>) {
        if (_state.value is ExportUiState.InProgress) return // 防重入
        job = appScope.launch {
            var lastPath = ""
            folders.forEachIndexed { index, folder ->
                _state.value = ExportUiState.InProgress(folder, index.toFloat() / folders.size)
                when (val r = exporter.exportSession(folder) { }) {
                    is ExportResult.Success -> lastPath = r.displayPath
                    is ExportResult.Error -> {
                        _state.value = ExportUiState.Failed(r.message)
                        return@launch
                    }
                    is ExportResult.InsufficientSpace -> {
                        _state.value = ExportUiState.InsufficientSpace(r.requiredBytes, r.availableBytes)
                        return@launch
                    }
                }
            }
            // 多夹导出: 同一批全落同一 rawdata 日期夹, 显示末个成功路径的父目录
            _state.value = ExportUiState.Done(if (folders.size == 1) lastPath else lastPath.substringBeforeLast('/', "Download/BlueTrace/rawdata"))
        }
    }

    fun reset() { _state.value = ExportUiState.Idle }
}
