package io.github.serabin1.tydl.domain.log

/**
 * The append-only log file's cap. When a write pushes the file past [MAX_BYTES], the oldest half
 * is dropped at a line boundary; the newest half is kept. Pure so rotation is testable without a
 * filesystem.
 */
object LogFile {
    const val MAX_BYTES = 256 * 1024

    fun trimToNewest(content: String, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return content

        val target = (maxBytes / 2).coerceAtLeast(1)
        var start = bytes.size - target
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        if (start >= bytes.size) {
            // A single enormous line: keep its tail rather than returning nothing.
            start = bytes.size - target
        } else {
            start++
        }
        return bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
    }
}
