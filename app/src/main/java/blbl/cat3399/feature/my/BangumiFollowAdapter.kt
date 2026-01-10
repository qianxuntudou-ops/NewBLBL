package blbl.cat3399.feature.my

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.databinding.ItemBangumiFollowBinding

class BangumiFollowAdapter(
    private val onClick: (position: Int, season: BangumiSeason) -> Unit,
) : RecyclerView.Adapter<BangumiFollowAdapter.Vh>() {
    private val items = ArrayList<BangumiSeason>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<BangumiSeason>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<BangumiSeason>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].seasonId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemBangumiFollowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemBangumiFollowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BangumiSeason, onClick: (position: Int, season: BangumiSeason) -> Unit) {
            binding.tvTitle.text = item.title

            val subtitle =
                when {
                    item.lastEpIndex != null -> "看到第${item.lastEpIndex}话"
                    item.newestEpIndex != null -> "更新至${item.newestEpIndex}话"
                    item.totalCount != null -> "共${item.totalCount}话"
                    else -> ""
                }
            binding.tvSubtitle.text = subtitle
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(pos, item)
            }
        }
    }
}
