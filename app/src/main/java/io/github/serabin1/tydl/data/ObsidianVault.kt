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
        context.contentResolver.openOutputStream(document.uri, "overwrite")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
        return true
    }

    fun createTodayFromTemplate(): Boolean {
        if (todayDocument() != null) return true
        val templateUri = settings.templateUri ?: return false
        val template = DocumentFile.fromSingleUri(context, Uri.parse(templateUri)) ?: return false
        val content = template.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { stream ->
                String(stream.readBytes(), Charsets.UTF_8)
            }
        } ?: return false
        return writeToday(content)
    }

    /** The hour of the day (0-23) after which the next day's note rolls over. */
    fun rolloverHour(): Int = settings.rolloverHour

    /** The content of the most recent note strictly before [before] (by daily-note filename). */
    fun latestNoteBefore(before: String): String? {
        val vaultUri = settings.vaultUri ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(vaultUri)) ?: return null
        val files: List<DocumentFile> = tree.listFiles()?.toList() ?: return null
        val latest = files
            .filter { !it.isDirectory && (it.name ?: "") < before }
            .sortedWith(compareByDescending { it.name ?: "" })
            .firstOrNull()
            ?: return null
        val uri = latest.uri
        return context.contentResolver.openInputStream(uri)?.use { stream ->
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

    fun todayFileName(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern(settings.filenameFormat.lowercase()))

    private fun todayDocument(): DocumentFile? {
        val vaultUri = settings.vaultUri ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(vaultUri))
        val today = tree?.findFile(todayFileName()) ?: return null
        return if (today.isFile) today else null
    }
}
