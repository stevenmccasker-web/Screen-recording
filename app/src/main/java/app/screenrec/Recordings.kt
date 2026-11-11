package app.screenrec

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val addedSec: Long,
)

object Recordings {

    /** Folder inside the phone's Movies directory where recordings are saved. */
    const val FOLDER = "Movies/ScreenRecorder"

    fun query(context: Context): List<Recording> {
        val out = ArrayList<Recording>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$FOLDER%")
        val order = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, args, order)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext() && out.size < 60) {
                val id = c.getLong(idCol)
                out.add(
                    Recording(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "Recording",
                        durationMs = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        addedSec = c.getLong(addedCol),
                    )
                )
            }
        }
        return out
    }
}

object Format {

    fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun date(seconds: Long): String =
        SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(Date(seconds * 1000))
}
