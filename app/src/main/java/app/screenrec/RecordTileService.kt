package app.screenrec

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to start recording from anywhere, tap again to stop. */
class RecordTileService : TileService() {

    private val listener: () -> Unit = { refreshTile() }

    override fun onStartListening() {
        super.onStartListening()
        RecorderState.listeners.add(listener)
        refreshTile()
    }

    override fun onStopListening() {
        RecorderState.listeners.remove(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderState.status == RecorderState.Status.IDLE) {
            launchCapture()
        } else {
            startService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        }
    }

    private fun launchCapture() {
        val intent = Intent(this, CaptureRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = RecorderState.status != RecorderState.Status.IDLE
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Stop recording" else "Record screen"
        tile.updateTile()
    }
}
