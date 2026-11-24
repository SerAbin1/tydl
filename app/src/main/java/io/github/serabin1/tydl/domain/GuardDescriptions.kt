package io.github.serabin1.tydl.domain

import io.github.serabin1.tydl.domain.obsidian.RolloverResult

/**
 * Stable, human-readable names for the reducer's inputs and outputs. R8 renames class names, so
 * `::class.simpleName` turns into `iy` in release; these string literals do not.
 *
 * Exhaustive `when`s make a new event or effect a compile error until it has a description.
 * Nothing here includes the session reason or the selected task text: those are user prose.
 */
fun GuardEvent.describe(): String = when (this) {
    is GuardEvent.Unlocked -> "Unlocked"
    is GuardEvent.DeadlineReached -> "DeadlineReached"
    is GuardEvent.Start -> "Start(minutes=$minutes, task=${if (task != null) "yes" else "no"})"
    GuardEvent.End -> "End"
    is GuardEvent.Extend -> "Extend(minutes=$minutes)"
    is GuardEvent.Paused -> if (until == 0L) "Resumed" else "Paused"
    is GuardEvent.ScreenOff -> "ScreenOff"
}

fun GuardEffect.describe(): String = when (this) {
    GuardEffect.None -> "None"
    GuardEffect.ShowPrompt -> "ShowPrompt"
    GuardEffect.ShowExpiry -> "ShowExpiry"
    GuardEffect.ShowDone -> "ShowDone"
}

fun RolloverResult.describe(): String = when (this) {
    is RolloverResult.Carried -> "carried $count from $from"
    is RolloverResult.AlreadyCarried -> "already carried ($count)"
    RolloverResult.NothingToCarry -> "nothing to carry"
    RolloverResult.NotConfigured -> "vault not configured"
    is RolloverResult.Failed -> "failed: $reason"
}
