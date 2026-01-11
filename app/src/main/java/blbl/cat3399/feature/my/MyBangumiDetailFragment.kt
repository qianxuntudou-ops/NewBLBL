package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.FragmentMyBangumiDetailBinding
import blbl.cat3399.feature.player.PlayerActivity
import kotlinx.coroutines.launch

class MyBangumiDetailFragment : Fragment() {
    private var _binding: FragmentMyBangumiDetailBinding? = null
    private val binding get() = _binding!!

    private val seasonId: Long by lazy { requireArguments().getLong(ARG_SEASON_ID) }
    private val isDrama: Boolean by lazy { requireArguments().getBoolean(ARG_IS_DRAMA) }

    private lateinit var epAdapter: BangumiEpisodeAdapter
    private var currentEpisodes: List<BangumiEpisode> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyBangumiDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStackImmediate() }
        binding.btnSecondary.text = if (isDrama) "已追剧" else "已追番"

        epAdapter =
            BangumiEpisodeAdapter {
                playEpisode(it)
            }
        binding.recyclerEpisodes.adapter = epAdapter
        binding.recyclerEpisodes.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        binding.btnPrimary.setOnClickListener {
            val first = currentEpisodes.firstOrNull()
            if (first == null) {
                Toast.makeText(requireContext(), "暂无可播放剧集", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playEpisode(first)
        }
        binding.btnSecondary.setOnClickListener {
            Toast.makeText(requireContext(), "暂不支持操作", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = BiliApi.bangumiSeasonDetail(seasonId = seasonId)
                binding.tvTitle.text = detail.title
                binding.tvDesc.text = detail.evaluate.orEmpty()

                val metaParts = buildList {
                    detail.subtitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                    detail.ratingScore?.let { add(String.format("%.1f分", it)) }
                    detail.views?.let { add("${Format.count(it)}次观看") }
                    detail.danmaku?.let { add("${Format.count(it)}条弹幕") }
                }
                binding.tvMeta.text = metaParts.joinToString(" | ")
                ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(detail.coverUrl))
                currentEpisodes = detail.episodes
                epAdapter.submit(detail.episodes)
            } catch (t: Throwable) {
                AppLog.e("MyBangumiDetail", "load failed seasonId=$seasonId", t)
                Toast.makeText(requireContext(), "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playEpisode(ep: BangumiEpisode) {
        val bvid = ep.bvid.orEmpty()
        val cid = ep.cid ?: -1L
        if (bvid.isBlank() || cid <= 0) {
            Toast.makeText(requireContext(), "缺少播放信息（bvid/cid）", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, bvid)
                .putExtra(PlayerActivity.EXTRA_CID, cid)
                .putExtra(PlayerActivity.EXTRA_EP_ID, ep.epId)
                .apply { ep.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } },
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SEASON_ID = "season_id"
        private const val ARG_IS_DRAMA = "is_drama"

        fun newInstance(seasonId: Long, isDrama: Boolean): MyBangumiDetailFragment =
            MyBangumiDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SEASON_ID, seasonId)
                    putBoolean(ARG_IS_DRAMA, isDrama)
                }
            }
    }
}
