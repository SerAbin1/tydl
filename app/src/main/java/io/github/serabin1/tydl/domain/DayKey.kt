package io.github.serabin1.tydl.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A type-safe ISO `yyyy-MM-dd` key for a Tydl day. Wrapping the string prevents a
 * filename-formatted date (e.g. `DD-MM-YYYY`) from being used where a stats key is expected.
 */
@JvmInline
value class DayKey(val value: String) : Comparable<DayKey> {
    init {
        require(ISO.matches(value)) { "DayKey must be ISO yyyy-MM-dd, got: $value" }
    }

    fun toLocalDate(): LocalDate = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)

    override fun compareTo(other: DayKey): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        private val ISO = Regex("""\d{4}-\d{2}-\d{2}""")

        fun of(date: LocalDate): DayKey = DayKey(date.format(DateTimeFormatter.ISO_LOCAL_DATE))

        fun parse(value: String): DayKey = DayKey(value)
    }
}
