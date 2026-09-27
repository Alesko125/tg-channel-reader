package ua.tgreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import ua.tgreader.databinding.ItemPostBinding
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PostAdapter(
    private val onClick: (Int) -> Unit,
    private val onLoadOlder: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var posts: List<Post> = emptyList()
    private var current = -1
    private var lastReadId = 0
    private var hasOlder = false

    private val header get() = if (hasOlder) 1 else 0

    fun positionOf(postIndex: Int) = postIndex + header

    fun submit(posts: List<Post>, current: Int, lastReadId: Int, hasOlder: Boolean) {
        val same = posts === this.posts && hasOlder == this.hasOlder
        val oldCurrent = this.current
        val oldLastRead = this.lastReadId
        this.posts = posts; this.current = current; this.lastReadId = lastReadId; this.hasOlder = hasOlder
        if (same && oldLastRead == lastReadId) {
            if (oldCurrent != current) {
                if (oldCurrent in posts.indices) notifyItemChanged(positionOf(oldCurrent))
                if (current in posts.indices) notifyItemChanged(positionOf(current))
            }
        } else {
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = posts.size + header

    override fun getItemViewType(position: Int) = if (position < header) TYPE_OLDER else TYPE_POST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_OLDER) {
            val button = inflater.inflate(R.layout.item_load_older, parent, false) as MaterialButton
            button.setOnClickListener { onLoadOlder() }
            object : RecyclerView.ViewHolder(button) {}
        } else {
            PostHolder(ItemPostBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is PostHolder) return
        val index = position - header
        val post = posts[index]
        val b = holder.b
        val ctx = b.root.context
        b.date.text = formatDate(post.date)
        b.text.text = post.text
        val isCurrent = index == current
        val isRead = post.id <= lastReadId && !isCurrent
        b.card.strokeWidth = if (isCurrent) (2 * ctx.resources.displayMetrics.density).toInt() else 0
        b.card.setCardBackgroundColor(
            MaterialColors.getColor(b.card, if (isCurrent) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow)
        )
        b.text.alpha = if (isRead) 0.6f else 1f
        b.nowPlaying.visibility = if (isCurrent) View.VISIBLE else View.GONE
        b.root.setOnClickListener { onClick(holder.bindingAdapterPosition - header) }
    }

    class PostHolder(val b: ItemPostBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val TYPE_OLDER = 0
        private const val TYPE_POST = 1
        private val FORMAT = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("uk"))

        fun formatDate(iso: String?): String = try {
            if (iso == null) "" else OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(FORMAT)
        } catch (e: Exception) {
            ""
        }
    }
}
