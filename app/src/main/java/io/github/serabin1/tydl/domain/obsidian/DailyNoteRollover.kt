package io.github.serabin1.tydl.domain.obsidian

import java.time.LocalDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The slice of the vault a rollover needs. The SAF-backed vault implements this; tests use an
 * in-memory fake, so the rollout rules stay free of Android.
 */
interface DailyNoteVault {
    fun isConfigured(): Boolean
    fun noteNameFor(now: LocalDateTime): String

    /** Ensures [name] exists with the template contents. Idempotent. */
    fun createFromTemplate(name: String): Boolean
    fun readNote(name: String): String?
    fun writeNote(name: String, content: String): Boolean
    fun listNoteNames(): List<String>
    fun filenameFormat(): String
}

/** What a rollover attempt actually did. Logging/diagnostics only: nothing is memoized. */
sealed interface RolloverResult {
    data class Carried(val count: Int, val from: String) : RolloverResult
    data class AlreadyCarried(val count: Int) : RolloverResult
    data object NothingToCarry : RolloverResult
    data object NotConfigured : RolloverResult
    data class Failed(val reason: String) : RolloverResult
}

/**
 * Idempotent daily-note rollover. Every call re-derives the answer from the vault's actual state,
 * so a deleted note, a missed day, or an earlier empty carry-over is repaired on the next trigger.
 *
 * Deliberately has no "already ran today" flag: a flag that survived an attempt which carried
 * nothing is what silently skipped rollover for the rest of the day.
 */
class DailyNoteRollover(private val vault: DailyNoteVault) {

    suspend fun ensureToday(now: LocalDateTime = LocalDateTime.now()): RolloverResult =
        mutex.withLock {
            if (!vault.isConfigured()) return@withLock RolloverResult.NotConfigured

            val todayName = vault.noteNameFor(now)
            if (!vault.createFromTemplate(todayName)) {
                return@withLock RolloverResult.Failed("cannot create $todayName from template")
            }
            val todayContent = vault.readNote(todayName)
                ?: return@withLock RolloverResult.Failed("cannot read $todayName")
            val today = ObsidianNotes.parse(todayContent, todayName)
            if (today.carryOverTasks.isNotEmpty()) {
                return@withLock RolloverResult.AlreadyCarried(today.carryOverTasks.size)
            }

            val previousName = ObsidianNotes.latestEarlierDailyNote(
                vault.listNoteNames(),
                todayName,
                vault.filenameFormat(),
            ) ?: return@withLock RolloverResult.NothingToCarry
            val previousContent = vault.readNote(previousName)
                ?: return@withLock RolloverResult.Failed("cannot read $previousName")
            val plan = ObsidianNotes.planRollover(todayContent, previousContent)
                ?: return@withLock RolloverResult.NothingToCarry

            if (!vault.writeNote(todayName, plan.updatedToday)) {
                return@withLock RolloverResult.Failed("cannot write $todayName")
            }
            val verify = vault.readNote(todayName)
                ?: return@withLock RolloverResult.Failed("cannot verify $todayName")
            if (ObsidianNotes.parse(verify).carryOverTasks.size < plan.carriedTasks.size) {
                return@withLock RolloverResult.Failed("verify failed for $todayName")
            }
            if (!vault.writeNote(previousName, plan.updatedPrevious)) {
                return@withLock RolloverResult.Failed("cannot update $previousName")
            }
            RolloverResult.Carried(plan.carriedTasks.size, previousName)
        }

    private companion object {
        /** Tile and guard share the process; one lock keeps their note writes from interleaving. */
        val mutex = Mutex()
    }
}
