package app.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Invisible activity that asks for the permissions and the system "start recording" prompt,
 * then hands over to [RecorderService]. Used by both the app button and the Quick Settings tile.
 */
class CaptureRequestActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.RECORD_AUDIO] == false) {
                Prefs(this).mic = false
                Toast.makeText(this, "Microphone is off, recording without sound", Toast.LENGTH_SHORT).show()
            }
            promptForProjection()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                // Get out of the way so the app isn't what ends up in the recording.
                moveTaskToBack(true)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) begin()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (RecorderState.status != RecorderState.Status.IDLE) {
            finish()
            return
        }
        val prefs = Prefs(this)
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            !prefs.askedNotifications &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            prefs.askedNotifications = true
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (prefs.mic && !granted(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) {
            promptForProjection()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun promptForProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
