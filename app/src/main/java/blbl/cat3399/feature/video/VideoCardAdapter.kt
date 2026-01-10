package blbl.cat3399.feature.video

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemVideoCardBinding

class VideoCardAdapter(
    private val onClick: (VideoCard) -> Unit,
) : RecyclerView.Adapter<VideoCardAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<VideoCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<VideoCard>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].bvid.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemVideoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemVideoCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VideoCard, onClick: (VideoCard) -> Unit) {
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text =
                item.pubDateText
                    ?: if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            binding.tvDuration.text = Format.duration(item.durationSec)
            binding.tvView.text = Format.count(item.view)
            binding.tvDanmaku.text = Format.count(item.danmaku)
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
