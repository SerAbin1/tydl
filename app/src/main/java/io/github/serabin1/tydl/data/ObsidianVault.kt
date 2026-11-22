package io.github.serabin1.tydl.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
        val templateUri = settings.templateUri ?: return false
        val template = DocumentFile.fromSingleUri(context, Uri.parse(templateUri)) ?: return false
        val content = template.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { stream ->
                String(stream.readBytes(), Charsets.UTF_8)
            }
        } ?: return false
        return writeToday(content)
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
