package io.github.serabin1.tydl.domain.obsidian

import io.github.serabin1.tydl.domain.TydlDay

/**
 * A task line from a daily note. The text is trimmed and the checkbox marker is stripped; the
 * indent is kept so a carried task can be written back indented.
 */
data class Task(
    val text: String,
    val checked: Boolean,
    val indent: Int = 0,
) {
    fun line(checked: Boolean): String = " ".repeat(indent) + "- [${if (checked) "x" else " "}] $text"
}

/**
 * The daily-note sections Tydl acts on. Anchors are `---` lines, never hardcoded contents, so
 * the note template can change without breaking the guard.
 */
enum class ObsidianSection { ROUTINE, BACKLOG, REFLECTION }

/** How the intent screen picks suggested tasks. */
enum class TaskGate { HARD, SOFT, MIXED }

/**
 * The slices of a daily note Tydl reads and writes: the routine tasks, the carried backlog
 * tasks, and the reflection. Pure: no Android, no filesystem.
 */
data class ObsidianNote(
    val filename: String,
    val routineTasks: List<Task>,
    val carryOverTasks: List<Task>,
    val reflection: String?,
) {
    /** Unchecked routine tasks still pending — the routine-first gate reads from here. */
    val pendingRoutineTasks: List<Task>
        get() = routineTasks.filter { !it.checked }
}

/**
 * Pure rules over Tydl's Obsidian daily notes. The vault itself is an Android concern; this
 * object decides what the notes mean and how Tydl mutates them.
 */
object ObsidianNotes {

    const val MAX_SUGGESTIONS = 3

    /** Formats a daily-note filename from a user-facing pattern (DD/MM/YYYY) using LocalDate. */
    fun dailyNoteName(format: String, date: java.time.LocalDate): String =
        date.format(dateFormatter(format)) + ".md"


    /** Parses a daily-note filename into its date, or null when it doesn't match the format. */
    fun parseDailyNoteDate(name: String, format: String): java.time.LocalDate? {
        val base = name.removeSuffix(".md")
        return runCatching {
            java.time.LocalDate.parse(base, dateFormatter(format))
        }.getOrNull()
    }

    /** Most recent earlier daily-note filename by actual date, ignoring non-matching files. */
    fun latestEarlierDailyNote(names: List<String>, beforeName: String, format: String): String? {
        val beforeDate = parseDailyNoteDate(beforeName, format) ?: return null
        return names
            .mapNotNull { n -> parseDailyNoteDate(n, format)?.let { n to it } }
            .filter { (_, d) -> d.isBefore(beforeDate) }
            .maxByOrNull { (_, d) -> d.toEpochDay() }
            ?.first
    }

    private fun dateFormatter(format: String): java.time.format.DateTimeFormatter {
        val pattern = format
            .replace("YYYY", "yyyy")
            .replace("YY", "yy")
            .replace("DD", "dd")
        return java.time.format.DateTimeFormatter.ofPattern(pattern)
    }

    private val taskRegex = Regex("""^\s*[-*]\s\[([ xX])\]\s*(.+)$""")
    private val whitespaceRegex = Regex("\\s+")

    fun parse(content: String, filename: String = ""): ObsidianNote {
        val routineTasks = mutableListOf<Task>()
        val carryOverTasks = mutableListOf<Task>()
        val reflectionLines = mutableListOf<String>()
        var section: ObsidianSection = ObsidianSection.ROUTINE

        content.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trim() == "---") {
                section = when (section) {
                    ObsidianSection.ROUTINE -> ObsidianSection.BACKLOG
                    ObsidianSection.BACKLOG -> ObsidianSection.REFLECTION
                    else -> ObsidianSection.REFLECTION
                }
                return@forEach
            }
            val task = line.toTask()
            when (section) {
                ObsidianSection.ROUTINE -> task?.let { routineTasks.add(it) }
                ObsidianSection.BACKLOG -> task?.let { carryOverTasks.add(it) }
                ObsidianSection.REFLECTION -> if (task == null) reflectionLines.add(line)
            }
        }

        val reflectionText = reflectionLines
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "-" && !it.startsWith(">") }
            .joinToString("\n")
        return ObsidianNote(
            filename = filename,
            routineTasks = routineTasks,
            carryOverTasks = carryOverTasks,
            reflection = reflectionText.ifBlank { null },
        )
    }

    /** Unchecked routine tasks still pending. */
    fun pendingRoutineTasks(content: String): List<Task> = parse(content).pendingRoutineTasks

    /** Unchecked backlog tasks waiting in the carry-over slot. */
    fun carryOverTasks(content: String): List<Task> =
        parse(content).carryOverTasks.filter { !it.checked }

    /**
     * Up to [MAX_SUGGESTIONS] tasks to show on the intent screen, shaped by [gate]:
     * HARD = routine while pending else backlog, SOFT = backlog only, MIXED = 1 routine + 2 backlog.
     */
    fun suggestions(
        note: ObsidianNote,
        gate: TaskGate = TaskGate.HARD,
        limit: Int = MAX_SUGGESTIONS,
    ): List<Task> {
        val routine = note.pendingRoutineTasks
        val backlog = note.carryOverTasks.filter { !it.checked }
        return when (gate) {
            TaskGate.HARD -> if (routine.isNotEmpty()) routine.take(limit) else backlog.take(limit)
            TaskGate.SOFT -> backlog.take(limit)
            TaskGate.MIXED -> (routine.take(1) + backlog).take(limit)
        }
    }

    /**
     * The R&T screen task list: the usual [suggestions] plus one extra task picked at random from
     * the backlog, so a routine day still surfaces an unrelated task. The shell supplies a random
     * index into the unseen backlog candidates; the rule stays pure and testable.
     *
     * Returns the suggestions with an unseen random backlog task appended, or just the suggestions
     * when there is no index or every backlog task is already shown.
     */
    fun suggestionsWithRandom(
        note: ObsidianNote,
        gate: TaskGate = TaskGate.HARD,
        limit: Int = MAX_SUGGESTIONS,
        randomCandidateIndex: Int?,
    ): List<Task> {
        val base = suggestions(note, gate, limit)
        val shown = base.map { it.text }.toSet()
        val candidates = note.carryOverTasks.filter { !it.checked && it.text !in shown }
        if (candidates.isEmpty() || randomCandidateIndex == null) return base
        val index = randomCandidateIndex % candidates.size
        return base + candidates[index]
    }

    /** Human-facing task text: wikilink alias, link text, or plain text. `[[Project]]: task` shows as "Project: task". */
    fun display(task: Task): String {
        val text = task.text
        val wikilinkStart = text.indexOf("[[")
        if (wikilinkStart >= 0) {
            val wikilinkEnd = text.indexOf("]]", wikilinkStart)
            if (wikilinkEnd >= 0) {
                val inner = text.substring(wikilinkStart + 2, wikilinkEnd)
                val parts = inner.split("|")
                val target = parts[0].trim()
                val alias = parts.getOrNull(1)?.trim()
                val project = alias?.takeIf { it.isNotEmpty() } ?: target.substringBefore('#').trim()
                val rest = text.substring(wikilinkEnd + 2).trim()
                return if (rest.startsWith(":")) "$project: ${rest.substring(1).trim()}".ifBlank { project } else project
            }
        }
        Regex("""\[([^\]]+)\]\([^)]*\)""").find(text)?.let {
            return it.groupValues[1].trim().ifBlank { task.text }
        }
        return task.text
    }

    /** Extracts first quote callout text from template content, including multi-line callout bodies. */
    fun templateQuote(content: String): String? {
        val lines = content.lines()
        for (i in lines.indices) {
            val header = Regex("""^>\s*\[!quote\]\s*(.*)$""", RegexOption.IGNORE_CASE)
                .find(lines[i].trim()) ?: continue
            val parts = mutableListOf<String>()
            header.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(parts::add)
            var j = i + 1
            while (j < lines.size) {
                val body = Regex("""^>\s?(.*)$""").find(lines[j].trim())?.groupValues?.get(1)?.trim()
                    ?: break
                if (body.isNotEmpty() && !body.startsWith("[!")) parts.add(body)
                j++
            }
            val quote = parts.joinToString(" ").trim()
            if (quote.isNotEmpty()) return quote
        }
        return null
    }

    /** The exact form a reason takes when it is written as a task line, used for later matching. */
    fun taskText(text: String): String = text.trim().replace(whitespaceRegex, " ")

    /**
     * The task a session remembers so its done screen can offer to check it off. A task picked
     * from the backlog wins, even if the add flag is set; a typed reason that is saved as a new
     * task becomes that task in the exact form the note stores, so checking it off works.
     */
    fun sessionTask(picked: String?, reason: String, addAsTask: Boolean): String? =
        picked ?: if (addAsTask) taskText(reason).takeIf { it.isNotEmpty() } else null

    /**
     * Adds one new task to the CARRY-OVER (backlog) slot. Used when a reason entered in R&T is
     * marked as a task: the sentence becomes an unchecked line in today's note.
     *
     * Blank text and a task that is already pending are no-ops, so re-tapping Start cannot litter
     * the backlog. A completed task with the same text may be added again as a new todo. Internal
     * whitespace is flattened so a pasted newline cannot break the markdown task line.
     */
    fun withTaskAdded(content: String, text: String): String {
        val normalized = taskText(text)
        if (normalized.isEmpty()) return content
        val alreadyPending = content.lineSequence().any { line ->
            val task = line.toTask()
            task != null && !task.checked && task.text == normalized
        }
        if (alreadyPending) return content
        return insertIntoCarryOver(content, listOf(Task(normalized, checked = false)))
    }

    /** Insert [tasks] into the CARRY-OVER slot, i.e. right after the first `---` anchor. */
    fun insertIntoCarryOver(content: String, tasks: List<Task>): String {
        val lines = content.split("\n")
        val first = separatorIndex(lines, 1)
        if (first < 0) return content
        val inserted = tasks.map { it.line(checked = false) }
        return (lines.subList(0, first + 1) + inserted + lines.subList(first + 1, lines.size))
            .joinToString("\n")
    }

    /**
     * Plan rollover: carry unchecked backlog from previous note into today's carry-over slot.
     * Returns null when nothing to carry or today's carry-over is not empty.
     */
    data class RolloverPlan(
        val updatedToday: String,
        val updatedPrevious: String,
        val carriedTasks: List<Task>,
    )

    fun planRollover(todayContent: String, previousContent: String): RolloverPlan? {
        val carried = carryOverTasks(previousContent)
        if (carried.isEmpty()) return null
        val today = parse(todayContent)
        if (today.carryOverTasks.isNotEmpty()) return null
        val updatedToday = insertIntoCarryOver(todayContent, carried)
        var updatedPrevious = previousContent
        carried.forEach { updatedPrevious = withTaskRemoved(updatedPrevious, it.text) }
        return RolloverPlan(updatedToday, updatedPrevious, carried)
    }

    /** Flip a task's checkbox in place: `- [ ]` becomes `- [x]` on that exact line. */
    fun withTaskChecked(content: String, taskText: String): String {
        val lines = content.split("\n").toMutableList()
        val index = lines.indexOfFirst { line ->
            val task = line.toTask() ?: return@indexOfFirst false
            task.text == taskText && !task.checked
        }
        if (index < 0) return content
        val task = lines[index].toTask()!!
        lines[index] = task.line(checked = true)
        return lines.joinToString("\n")
    }

    /** Remove a task line (the one that was carried over) from a note. */
    fun withTaskRemoved(content: String, taskText: String): String {
        val lines = content.split("\n").toMutableList()
        val index = lines.indexOfFirst { line ->
            val task = line.toTask() ?: return@indexOfFirst false
            task.text == taskText
        }
        if (index < 0) return content
        lines.removeAt(index)
        return lines.joinToString("\n")
    }

    /**
     * Set the reflection. The first reflection of the day replaces the lone `- ` placeholder;
     * later ones append below, above the closing quote.
     */
    fun withReflection(content: String, text: String): String {
        val lines = content.split("\n").toMutableList()
        val second = separatorIndex(lines, 2)
        if (second < 0) return content
        val quoteIdx = (second + 1 until lines.size).firstOrNull { lines[it].trim().startsWith(">") }
        val end = quoteIdx ?: lines.size
        val region = lines.subList(second + 1, end).toMutableList()
        val existing = region.map { it.trim() }
            .filter { it.isNotEmpty() && it != "-" && !it.startsWith(">") }
        val newBullet = "- ${text.trim()}"
        val bullets = if (existing.isEmpty()) listOf(newBullet) else existing + newBullet
        return (lines.subList(0, second + 1) + bullets + lines.subList(end, lines.size))
            .joinToString("\n")
    }

    private fun String.toTask(): Task? =
        taskRegex.matchEntire(trim())?.let { match ->
            Task(
                text = match.groupValues[2].trim(),
                checked = match.groupValues[1].equals("x", ignoreCase = true),
                indent = this.takeWhile { it.isWhitespace() }.length,
            )
        }

    private fun separatorIndex(lines: List<String>, ordinal: Int): Int {
        var seen = 0
        for (index in lines.indices) {
            if (lines[index].trim() == "---") {
                seen++
                if (seen == ordinal) return index
            }
        }
        return -1
    }
}
