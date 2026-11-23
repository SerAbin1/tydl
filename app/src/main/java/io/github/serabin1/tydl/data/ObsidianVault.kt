package io.github.serabin1.tydl.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.serabin1.tydl.domain.obsidian.ObsidianNotes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Reads and writes Tydl's Obsidian daily notes through the Storage Access Framework. The vault
 * folder is picked once by the user and remembered as a content URI; before that, or when the
 * vault is missing/unreadable, the guard falls back to the plain free-text prompt.
 */
class ObsidianVault(private val context: Context) {

    private val settingsStore = ObsidianSettingsStore(context)
    private val settings get() = settingsStore.load()

    fun isConfigured(): Boolean = settings.vaultUri != null

    fun hasToday(): Boolean = todayDocument() != null

    fun readToday(): String? = todayDocument()?.uri?.let { uri ->
        context.contentResolver.openInputStream(uri)?.use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }
    }

    fun writeToday(content: String): Boolean {
        val document = todayDocument() ?: return false
        return writeDocument(document, content)
    }

    fun writeNote(name: String, content: String): Boolean {
        val document = findFile(name) ?: return false
        return writeDocument(document, content)
    }

    fun createTodayFromTemplate(): Boolean {
        val expected = todayFileName()
        if (findFile(expected) != null) return true
        val templateContent = readTemplate() ?: return false
        val tree = vaultTree() ?: return false
        val created = tree.createFile("text/markdown", expected) ?: return false
        if (created.name != expected) {
            android.util.Log.w("Tydl", "createTodayFromTemplate: SAF renamed $expected to ${created.name}, deleting stray")
            created.delete()
            return findFile(expected) != null
        }
        val written = writeDocument(created, templateContent)
        android.util.Log.i("Tydl", "createTodayFromTemplate: created=$expected written=$written")
        return written
    }

    fun readTemplate(): String? {
        val templateUri = settings.templateUri ?: return null
        val template = DocumentFile.fromSingleUri(context, Uri.parse(templateUri)) ?: return null
        return context.contentResolver.openInputStream(template.uri)?.use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }
    }

    private fun writeDocument(document: DocumentFile, content: String): Boolean {
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        return true
    }

    /** The hour of the day (0-23) after which the next day's note rolls over. */
    fun rolloverHour(): Int = settings.rolloverHour

    fun filenameFormat(): String = settings.filenameFormat

    fun taskGate(): io.github.serabin1.tydl.domain.obsidian.TaskGate = settings.taskGate

    fun listDailyNoteNames(): List<String> {
        val tree = vaultTree() ?: return emptyList()
        return tree.listFiles().mapNotNull { if (!it.isDirectory) it.name else null }
    }

    fun readNote(name: String): String? {
        val file = findFile(name) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }
    }

    /** The content of the most recent daily note strictly before [beforeName], by date. */
    fun latestNoteBefore(beforeName: String): String? {
        val vaultUri = settings.vaultUri ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(vaultUri)) ?: return null
        val files = tree.listFiles().toList().filter { !it.isDirectory }
        val format = settings.filenameFormat
        val latestName = ObsidianNotes.latestEarlierDailyNote(
            files.mapNotNull { it.name },
            beforeName,
            format,
        ) ?: return null
        val latest = files.firstOrNull { it.name == latestName } ?: return null
        return context.contentResolver.openInputStream(latest.uri)?.use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }
    }

    /**
     * Checks the picked task off in today's note: `- [ ]` becomes `- [x]` on that exact line, in
     * place, via the pure flip and a write-verify style overwrite.
     */
    fun completeTask(taskText: String): Boolean {
        if (!isConfigured()) return false
        val content = readToday() ?: return false
        val updated = ObsidianNotes.withTaskChecked(content, taskText)
        if (updated == content) return false
        return writeToday(updated)
    }

    /** Appends the how-it-went reflection, below any earlier reflection, above the closing quote. */
    fun appendReflection(note: String): Boolean {
        if (!isConfigured()) return false
        val content = readToday() ?: return false
        return writeToday(ObsidianNotes.withReflection(content, note))
    }

    fun todayFileName(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String =
        ObsidianNotes.dailyNoteName(
            settings.filenameFormat,
            ObsidianNotes.effectiveDate(now, settings.rolloverHour),
        )

    private fun vaultTree(): DocumentFile? {
        val vaultUri = settings.vaultUri ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(vaultUri))
    }

    private fun findFile(name: String): DocumentFile? {
        val tree = vaultTree() ?: return null
        val file = tree.findFile(name) ?: return null
        return if (file.isFile) file else null
    }

    private fun todayDocument(): DocumentFile? = findFile(todayFileName())
}
