package io.github.serabin1.tydl.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.serabin1.tydl.R
import io.github.serabin1.tydl.data.ObsidianVault
import io.github.serabin1.tydl.domain.obsidian.ObsidianUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens the daily note that is current by Tydl's rollover hour, not by the calendar day.
 * The date-to-URI logic lives in the pure domain; this service only reads settings and launches
 * Obsidian. It deliberately does not create the note: the guard owns creation and rollover, and
 * a missing note should fail visibly rather than be invented by a tile tap.
 */
class DailyNoteTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vault by lazy { ObsidianVault(this) }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = getString(R.string.tile_daily_note_label)
            tile.contentDescription = tile.label
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val name = withContext(Dispatchers.IO) { vault.todayFileName() }
            openInObsidian(ObsidianUris.openNote(name))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun openInObsidian(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws on API 34+; the system requires a PendingIntent there.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
