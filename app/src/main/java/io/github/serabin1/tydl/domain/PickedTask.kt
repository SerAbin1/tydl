package io.github.serabin1.tydl.domain

/**
 * A task the user picked in the R&T screen. The [raw] form is the exact text from the daily note
 * (the identity used for check-off and note matching); [display] is the human-facing projection
 * shown on the button and pre-filled into the reason field. Keeping both avoids re-deriving the
 * display form from storage and keeps the two normalised forms from being confused.
 */
data class PickedTask(
    val raw: String,
    val display: String,
)