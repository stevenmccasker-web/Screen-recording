package app.screenrec

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Shared recording status so the screen, notification and Quick Settings tile stay in sync. */
object RecorderState {

    enum class Status { IDLE, STARTING, RECORDING, PAUSED }

    @Volatile
    var status: Status = Status.IDLE
        private set

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private var accumulatedMs = 0L
    private var resumedAt = 0L

    fun elapsedMs(): Long =
        if (status == Status.RECORDING) {
            accumulatedMs + (SystemClock.elapsedRealtime() - resumedAt)
        } else {
            accumulatedMs
        }

    fun starting() {
        accumulatedMs = 0L
        set(Status.STARTING)
    }

    fun recording() {
        accumulatedMs = 0L
        resumedAt = SystemClock.elapsedRealtime()
        set(Status.RECORDING)
    }

    fun paused() {
        accumulatedMs += SystemClock.elapsedRealtime() - resumedAt
        set(Status.PAUSED)
    }

    fun resumed() {
        resumedAt = SystemClock.elapsedRealtime()
        set(Status.RECORDING)
    }

    fun idle() {
        accumulatedMs = 0L
        set(Status.IDLE)
    }

    private fun set(newStatus: Status) {
        status = newStatus
        main.post { listeners.forEach { it() } }
    }
}
