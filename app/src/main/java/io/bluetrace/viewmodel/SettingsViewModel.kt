package io.bluetrace.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.R
import io.bluetrace.data.android.ExportResult
import io.bluetrace.data.android.MediaStoreExporter
import io.bluetrace.shared.session.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StorageBreakdown(
    val rawBytes: Long = 0,
    val csvBytes: Long = 0,
    val comboBytes: Long = 0,
    val gnssBytes: Long = 0,
    val manifestBytes: Long = 0,
    val totalBytes: Long = 0,
    val sessionCount: Int = 0,
    val fileCount: Int = 0,
)

/** 设置 Tab + 子页（环境/GNSS/导出位置/存储/应用日志/关于）。 */
class SettingsViewModel(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
    private val exporter: MediaStoreExporter,
) : ViewModel() {

    val logEntries = diagnostics.entries

    private val _storage = MutableStateFlow(StorageBreakdown())
    val storage: StateFlow<StorageBreakdown> = _storage

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    fun clearLog() = diagnostics.clear()

    fun exportLog() {
        viewModelScope.launch {
            val r = exporter.exportLog(diagnostics.export(), "bluetrace_log.txt")
            _toast.value = when (r) {
                is ExportResult.Success -> context.getString(R.string.log_export_done, r.displayPath)
                is ExportResult.Error -> context.getString(R.string.log_export_failed, r.message)
                is ExportResult.InsufficientSpace -> context.getString(R.string.export_need_space_title)
            }
        }
    }

    fun consumeToast() { _toast.value = null }

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { computeStorage() }
        }
    }

    private fun computeStorage(): StorageBreakdown {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val sessionsDir = File(base, "sessions")
        if (!sessionsDir.exists()) return StorageBreakdown()
        var raw = 0L; var csv = 0L; var combo = 0L; var gnss = 0L; var manifest = 0L; var files = 0
        val sessions = sessionsDir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        sessions.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                files++
                val size = f.length()
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                when {
                    rel.startsWith("raw/") -> raw += size
                    rel == "session_manifest.json" -> manifest += size
                    rel == "gps.csv" -> gnss += size
                    rel.endsWith("ppg_acc.csv") -> combo += size
                    rel.endsWith(".csv") -> csv += size
                    else -> manifest += size
                }
            }
        }
        val total = raw + csv + combo + gnss + manifest
        return StorageBreakdown(raw, csv, combo, gnss, manifest, total, sessions.size, files)
    }
}
