package app.screenrec

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.screenrec.RecorderState.Status
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnRecord: MaterialButton
    private lateinit var activeRow: View
    private lateinit var btnPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var emptyText: TextView
    private lateinit var recordingsAdapter: RecordingsAdapter

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastStatus = Status.IDLE

    private val ticker = object : Runnable {
        override fun run() {
            renderTimer()
            ui.postDelayed(this, 250)
        }
    }

    private val stateListener: () -> Unit = { renderState() }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            loadRecordings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        timerText = findViewById(R.id.timerText)
        statusText = findViewById(R.id.statusText)
        btnRecord = findViewById(R.id.btnRecord)
        activeRow = findViewById(R.id.activeRow)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        emptyText = findViewById(R.id.emptyText)

        recordingsAdapter = RecordingsAdapter(this, ::openRecording, ::shareRecording, ::confirmDelete)
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recordingsAdapter
            isNestedScrollingEnabled = false
        }

        setupOptions()

        btnRecord.setOnClickListener {
            startActivity(Intent(this, CaptureRequestActivity::class.java))
        }
        btnStop.setOnClickListener { sendToService(RecorderService.ACTION_STOP) }
        btnPause.setOnClickListener {
            val resume = RecorderState.status == Status.PAUSED
            sendToService(if (resume) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
        }
    }

    override fun onStart() {
        super.onStart()
        RecorderState.listeners.add(stateListener)
        lastStatus = RecorderState.status
        renderState()
        ui.post(ticker)
        loadRecordings()
    }

    override fun onStop() {
        RecorderState.listeners.remove(stateListener)
        ui.removeCallbacks(ticker)
        super.onStop()
    }

    // ---------------------------------------------------------------- options

    private fun setupOptions() {
        bindGroup(
            R.id.groupResolution,
            mapOf(R.id.res720 to 720, R.id.res1080 to 1080, R.id.resNative to 0),
            prefs.resolution,
        ) { prefs.resolution = it }

        bindGroup(
            R.id.groupFps,
            mapOf(R.id.fps30 to 30, R.id.fps60 to 60),
            prefs.fps,
        ) { prefs.fps = it }

        bindGroup(
            R.id.groupQuality,
            mapOf(R.id.qLow to 0, R.id.qMed to 1, R.id.qHigh to 2),
            prefs.quality,
        ) { prefs.quality = it }

        bindGroup(
            R.id.groupCountdown,
            mapOf(R.id.cd0 to 0, R.id.cd3 to 3, R.id.cd5 to 5),
            prefs.countdown,
        ) { prefs.countdown = it }

        val mic = findViewById<MaterialSwitch>(R.id.micSwitch)
        mic.isChecked = prefs.mic
        mic.setOnCheckedChangeListener { _, checked -> prefs.mic = checked }
    }

    private fun bindGroup(
        groupId: Int,
        options: Map<Int, Int>,
        current: Int,
        onChange: (Int) -> Unit,
    ) {
        val group = findViewById<MaterialButtonToggleGroup>(groupId)
        val initial = options.entries.firstOrNull { it.value == current }?.key ?: options.keys.first()
        group.check(initial)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) options[checkedId]?.let(onChange)
        }
    }

    // ------------------------------------------------------------------ state

    private fun renderState() {
        val s = RecorderState.status
        val idle = s == Status.IDLE
        btnRecord.visibility = if (idle) View.VISIBLE else View.GONE
        activeRow.visibility = if (idle) View.GONE else View.VISIBLE
        btnPause.isEnabled = s == Status.RECORDING || s == Status.PAUSED
        btnPause.text = if (s == Status.PAUSED) "Resume" else "Pause"
        btnStop.text = if (s == Status.STARTING) "Cancel" else "Stop"
        statusText.text = when (s) {
            Status.IDLE -> "Ready to record"
            Status.STARTING -> "Starting…"
            Status.RECORDING -> "Recording"
            Status.PAUSED -> "Paused"
        }
        renderTimer()
        if (idle && lastStatus != Status.IDLE) loadRecordings()
        lastStatus = s
    }

    private fun renderTimer() {
        timerText.text = Format.clock(RecorderState.elapsedMs())
    }

    private fun sendToService(action: String) {
        startService(Intent(this, RecorderService::class.java).setAction(action))
    }

    // ------------------------------------------------------------- recordings

    private fun loadRecordings() {
        io.execute {
            val list = Recordings.query(this)
            ui.post {
                recordingsAdapter.submit(list)
                emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openRecording(r: Recording) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(r.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to play this video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecording(r: Recording) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share recording"))
    }

    private fun confirmDelete(r: Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete recording?")
            .setMessage(r.name)
            .setPositiveButton("Delete") { _, _ -> deleteRecording(r) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRecording(r: Recording) {
        try {
            contentResolver.delete(r.uri, null, null)
            loadRecordings()
        } catch (e: SecurityException) {
            // Not created by this install of the app, so Android asks the user to confirm.
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(r.uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }
}
