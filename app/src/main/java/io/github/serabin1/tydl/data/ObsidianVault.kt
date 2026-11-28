package io.github.serabin1.tydl.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.serabin1.tydl.domain.TydlDay
import io.github.serabin1.tydl.domain.obsidian.DailyNoteVault
import io.github.serabin1.tydl.domain.obsidian.ObsidianNotes
import io.github.serabin1.tydl.domain.log.LogTag
import io.github.serabin1.tydl.log.TydlLog
import java.time.LocalDateTime

/**
 * Reads and writes Tydl's Obsidian daily notes through the Storage Access Framework. The vault
 * folder is picked once by the user and remembered as a content URI; before that, or when the
 * vault is missing/unreadable, the guard falls back to the plain free-text prompt.
 */
class ObsidianVault(private val context: Context) : DailyNoteVault {

    private val settingsStore = TydlSettingsStore(context)
    private val settings get() = settingsStore.load()

    override fun isConfigured(): Boolean = settings.vaultUri != null

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

    override fun writeNote(name: String, content: String): Boolean {
        val document = findFile(name) ?: return false
        return writeDocument(document, content)
    }

    /** Ensures [name] exists with the template contents. Idempotent: an existing file is untouched. */
    override fun createFromTemplate(name: String): Boolean {
        if (findFile(name) != null) return true
        val templateContent = readTemplate() ?: run {
            TydlLog.w(LogTag.VAULT, "createFromTemplate: no template selected for $name")
            return false
        }
        val tree = vaultTree() ?: run {
            TydlLog.w(LogTag.VAULT, "createFromTemplate: vault folder unavailable")
            return false
        }
        val created = tree.createFile("text/markdown", name) ?: run {
            TydlLog.w(LogTag.VAULT, "createFromTemplate: SAF refused to create $name")
            return false
        }
        if (created.name != name) {
            TydlLog.w(LogTag.VAULT, "createFromTemplate: SAF renamed $name to ${created.name}, deleting stray")
            created.delete()
            return findFile(name) != null
        }
        val written = writeDocument(created, templateContent)
        TydlLog.i(LogTag.VAULT, "createFromTemplate: created=$name written=$written")
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

    /** The user-configured daily-note filename pattern, e.g. DD-MM-YYYY. */
    override fun filenameFormat(): String = settings.filenameFormat

    fun taskGate(): io.github.serabin1.tydl.domain.obsidian.TaskGate = settings.taskGate

    override fun listNoteNames(): List<String> {
        val tree = vaultTree() ?: return emptyList()
        return tree.listFiles().mapNotNull { if (!it.isDirectory) it.name else null }
    }

    override fun readNote(name: String): String? {
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

    /** Adds a task to today's BACKLOG slot, e.g. a reason marked as a task in R&T. */
    fun addTask(text: String): Boolean {
        if (!isConfigured()) return false
        val content = readToday() ?: return false
        val updated = ObsidianNotes.withTaskAdded(content, text)
        if (updated == content) return false
        return writeToday(updated)
    }

    /** Appends the how-it-went reflection, below any earlier reflection, above the closing quote. */
    fun appendReflection(note: String): Boolean {
        if (!isConfigured()) return false
        val content = readToday() ?: return false
        return writeToday(ObsidianNotes.withReflection(content, note))
    }

    fun todayFileName(now: LocalDateTime = LocalDateTime.now()): String {
        val current = settings
        return ObsidianNotes.dailyNoteName(
            current.filenameFormat,
            TydlDay.of(now, current.rolloverHour),
        )
    }

    override fun noteNameFor(now: LocalDateTime): String = todayFileName(now)

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
