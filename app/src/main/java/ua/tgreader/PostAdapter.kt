package ua.tgreader

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
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
    private var state = ReaderState()
    private var hasOlder = false
    private var settings = AppSettings()

    private val header get() = if (hasOlder) 1 else 0

    fun positionOf(postIndex: Int) = postIndex + header

    fun submit(s: ReaderState, settings: AppSettings) {
        val same = s.posts === posts && s.hasOlder == hasOlder && s.lastRead == state.lastRead &&
            settings == this.settings && s.channels == state.channels
        val oldCurrent = current
        posts = s.posts; current = s.current; state = s; hasOlder = s.hasOlder; this.settings = settings
        if (same) {
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
        val date = formatDate(post.date)
        // In the shared feed every post says which channel it is from.
        b.date.text = if (state.isFeed) listOf(state.titleOf(post.channel), date).filter { it.isNotEmpty() }.joinToString(" · ") else date
        b.text.text = post.text
        b.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * settings.fontScale / 100f)
        val isCurrent = index == current
        val isRead = state.isRead(post) && !isCurrent
        b.card.strokeWidth = if (isCurrent) (2 * ctx.resources.displayMetrics.density).toInt() else 0
        b.card.setCardBackgroundColor(
            MaterialColors.getColor(
                b.card,
                if (isCurrent) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
        )
        b.text.alpha = if (isRead) 0.6f else 1f
        b.nowPlaying.visibility = if (isCurrent) View.VISIBLE else View.GONE
        if (settings.showPhotos && post.photo != null) {
            b.photo.visibility = View.VISIBLE
            b.photo.load(post.photo) { crossfade(true) }
        } else {
            b.photo.visibility = View.GONE
            b.photo.setImageDrawable(null)
        }
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
