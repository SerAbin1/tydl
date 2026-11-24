package io.github.serabin1.tydl.domain.log

/**
 * The bounded in-memory tail the log screen observes. The file has its own byte cap; this keeps
 * the UI cheap no matter how long the process lives.
 */
class LogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayDeque<LogEntry>(capacity)

    val entries: List<LogEntry> get() = items.toList()

    fun add(entry: LogEntry): List<LogEntry> {
        items.addLast(entry)
        while (items.size > capacity) items.removeFirst()
        return entries
    }

    fun replaceAll(entries: List<LogEntry>): List<LogEntry> {
        items.clear()
        entries.takeLast(capacity).forEach { items.addLast(it) }
        return this.entries
    }

    fun clear(): List<LogEntry> {
        items.clear()
        return emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1000
    }
}
