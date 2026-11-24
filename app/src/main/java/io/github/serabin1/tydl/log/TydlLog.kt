package io.github.serabin1.tydl.log

import android.content.Context
import android.util.Log
import io.github.serabin1.tydl.domain.log.LogBuffer
import io.github.serabin1.tydl.domain.log.LogEntry
import io.github.serabin1.tydl.domain.log.LogFile
import io.github.serabin1.tydl.domain.log.LogFormat
import io.github.serabin1.tydl.domain.log.LogTag
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's single log sink. One append-only file, capped by [LogFile], plus a bounded in-memory
 * tail that the log screen observes. Callers never block: they hand an entry to a channel and the
 * writer coroutine owns the file. Everything is still mirrored to logcat for `adb logcat`.
 *
 * Initialised from [io.github.serabin1.tydl.TydlApplication] so the guard has a sink even when
 * the UI process has never opened.
 */
object TydlLog {
    private const val TAG = "Tydl"
    private const val FILE_NAME = "tydl.log"

    private sealed interface Command {
        data class Write(val entry: LogEntry) : Command
        data object Clear : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val buffer = LogBuffer()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    private var file: File? = null

    @Volatile
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val logFile = File(context.applicationContext.filesDir, FILE_NAME)
        file = logFile
        val loaded = runCatching {
            logFile.readLines().mapNotNull { LogFormat.parse(it) }
        }.getOrDefault(emptyList())
        _entries.value = buffer.replaceAll(loaded)
        scope.launch { consume() }
    }

    fun i(tag: LogTag, message: String) = emit(tag, message, Log.INFO, null)

    fun w(tag: LogTag, message: String, error: Throwable? = null) =
        emit(tag, message, Log.WARN, error)

    fun e(tag: LogTag, message: String, error: Throwable? = null) =
        emit(tag, message, Log.ERROR, error)

    fun clear() {
        commands.trySend(Command.Clear)
    }

    private fun emit(tag: LogTag, message: String, priority: Int, error: Throwable?) {
        val text = if (error == null) message else "$message — ${error.describe()}"
        Log.println(priority, TAG, "[${tag.label}] $text")
        if (!started) return
        commands.trySend(Command.Write(LogEntry(System.currentTimeMillis(), tag, text)))
    }

    private suspend fun consume() {
        for (command in commands) {
            when (command) {
                is Command.Write -> {
                    _entries.value = buffer.add(command.entry)
                    append(command.entry)
                }

                Command.Clear -> {
                    _entries.value = buffer.clear()
                    runCatching { file?.delete() }
                }
            }
        }
    }

    private fun append(entry: LogEntry) {
        val target = file ?: return
        runCatching {
            if (target.length() > LogFile.MAX_BYTES) {
                target.writeText(LogFile.trimToNewest(target.readText()))
            }
            target.appendText(LogFormat.line(entry) + "\n")
        }.onFailure { Log.w(TAG, "log file write failed", it) }
    }

    /** No class names: R8 renames them, and the message is the useful part. */
    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: "exception"
}
