package cn.edu.whu.libseat.core.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val line: String,
    val level: Char,
)

/**
 * 轻量运行日志：内存供设置页查看，同时落盘到 files/logs/app.log。
 * 用于排查验证码 WebView / 接口问题，用户可在设置里一键复制。
 */
object AppLog {
    private const val MAX = 1500
    private const val FILE_NAME = "app.log"

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val lock = Any()
    private var file: File? = null

    fun install(context: Context) {
        val f = File(File(context.filesDir, "logs"), FILE_NAME)
        runCatching { f.parentFile?.mkdirs() }
        file = f
        val existing = runCatching {
            if (f.exists()) f.readLines().takeLast(500) else emptyList()
        }.getOrDefault(emptyList())
        _entries.value = existing.map { line ->
            val level = LEVEL_PATTERN.find(line)?.groupValues?.getOrNull(1)?.firstOrNull() ?: 'I'
            LogEntry(line, level)
        }
        i("App", "日志已启动，文件：${f.absolutePath}")
    }

    fun d(tag: String, message: String) = write('D', tag, message)

    fun i(tag: String, message: String) = write('I', tag, message)

    fun w(tag: String, message: String) = write('W', tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) {
            message
        } else {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        write('E', tag, detail)
    }

    fun exportText(): String = synchronized(lock) {
        _entries.value.joinToString("\n") { it.line }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            runCatching { file?.writeText("") }
        }
    }

    private fun write(level: Char, tag: String, message: String) {
        val line = format(level, tag, message)
        synchronized(lock) {
            _entries.value = (_entries.value + LogEntry(line, level)).takeLast(MAX)
            runCatching { file?.appendText(line + "\n") }
        }
        Log.println(priority(level), tag, message)
    }

    private fun format(level: Char, tag: String, message: String): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return "$time $level/$tag: $message"
    }

    private fun priority(level: Char): Int = when (level) {
        'E' -> Log.ERROR
        'W' -> Log.WARN
        'D' -> Log.DEBUG
        else -> Log.INFO
    }

    private val LEVEL_PATTERN = Regex("""\s([DIWE])/""")
}
