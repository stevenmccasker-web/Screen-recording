package app.screenrec

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class RecordingsAdapter(
    private val context: Context,
    private val onOpen: (Recording) -> Unit,
    private val onShare: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit,
) : RecyclerView.Adapter<RecordingsAdapter.Holder>() {

    private var items: List<Recording> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = LruCache<Long, Bitmap>(60)

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val name: TextView = view.findViewById(R.id.name)
        val meta: TextView = view.findViewById(R.id.meta)
        val share: ImageButton = view.findViewById(R.id.btnShare)
        val delete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    fun submit(list: List<Recording>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        holder.name.text = r.name
        holder.meta.text =
            "${Format.clock(r.durationMs)} · ${Formatter.formatShortFileSize(context, r.size)} · ${Format.date(r.addedSec)}"
        holder.itemView.setOnClickListener { onOpen(r) }
        holder.share.setOnClickListener { onShare(r) }
        holder.delete.setOnClickListener { onDelete(r) }

        holder.thumb.tag = r.id
        val cached = cache.get(r.id)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.thumb.setImageDrawable(null)
            loader.execute {
                val bitmap = try {
                    context.contentResolver.loadThumbnail(r.uri, Size(240, 360), null)
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    main.post {
                        cache.put(r.id, bitmap)
                        if (holder.thumb.tag == r.id) holder.thumb.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
