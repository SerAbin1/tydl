package io.github.serabin1.tydl.domain.obsidian

/**
 * A task line from a daily note. The text is trimmed and the checkbox marker is stripped; the
 * indent is kept so a carried task can be written back indented.
 */
data class ObsidianTask(
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
enum class ObsidianBlock { ROUTINE, CARRY_OVER, REFLECTION }

/** How the intent screen picks suggested tasks. */
enum class TaskGate { HARD, SOFT, MIXED }

/**
 * The slices of a daily note Tydl reads and writes: the routine tasks, the carried backlog
 * tasks, and the reflection. Pure: no Android, no filesystem.
 */
data class ObsidianNote(
    val filename: String,
    val routineTasks: List<ObsidianTask>,
    val carryOverTasks: List<ObsidianTask>,
    val reflection: String?,
) {
    /** Unchecked routine tasks still pending — the routine-first gate reads from here. */
    val pendingRoutineTasks: List<ObsidianTask>
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
        date.format(dateFormatter(format))

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

    fun parse(content: String, filename: String = ""): ObsidianNote {
        val routineTasks = mutableListOf<ObsidianTask>()
        val carryOverTasks = mutableListOf<ObsidianTask>()
        val reflectionLines = mutableListOf<String>()
        var section: ObsidianBlock = ObsidianBlock.ROUTINE

        content.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trim() == "---") {
                section = when (section) {
                    ObsidianBlock.ROUTINE -> ObsidianBlock.CARRY_OVER
                    ObsidianBlock.CARRY_OVER -> ObsidianBlock.REFLECTION
                    else -> ObsidianBlock.REFLECTION
                }
                return@forEach
            }
            val task = line.toTask()
            when (section) {
                ObsidianBlock.ROUTINE -> task?.let { routineTasks.add(it) }
                ObsidianBlock.CARRY_OVER -> task?.let { carryOverTasks.add(it) }
                ObsidianBlock.REFLECTION -> if (task == null) reflectionLines.add(line)
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
    fun pendingRoutineTasks(content: String): List<ObsidianTask> = parse(content).pendingRoutineTasks

    /** Unchecked backlog tasks waiting in the carry-over slot. */
    fun carryOverTasks(content: String): List<ObsidianTask> =
        parse(content).carryOverTasks.filter { !it.checked }

    /**
     * Up to [MAX_SUGGESTIONS] tasks to show on the intent screen, shaped by [gate]:
     * HARD = routine while pending else backlog, SOFT = backlog only, MIXED = 1 routine + 2 backlog.
     */
    fun suggestions(
        note: ObsidianNote,
        gate: TaskGate = TaskGate.HARD,
        limit: Int = MAX_SUGGESTIONS,
    ): List<ObsidianTask> {
        val routine = note.pendingRoutineTasks
        val backlog = note.carryOverTasks.filter { !it.checked }
        return when (gate) {
            TaskGate.HARD -> if (routine.isNotEmpty()) routine.take(limit) else backlog.take(limit)
            TaskGate.SOFT -> backlog.take(limit)
            TaskGate.MIXED -> (routine.take(1) + backlog).take(limit)
        }
    }

    /** Human-facing task text: wikilink alias, link text, or plain text. `[[Project]]: task` shows as "Project: task". */
    fun display(task: ObsidianTask): String {
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
        Regex("""\[[^\]]+\]\([^)]*\)""").find(text)?.let {
            return it.groupValues[1].trim().ifBlank { task.text }
        }
        return task.text
    }

    /** Insert [tasks] into the CARRY-OVER slot, i.e. right after the first `---` anchor. */
    fun insertIntoCarryOver(content: String, tasks: List<ObsidianTask>): String {
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
        val carriedTasks: List<ObsidianTask>,
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

    private fun String.toTask(): ObsidianTask? =
        taskRegex.matchEntire(trim())?.let { match ->
            ObsidianTask(
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
