package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.databinding.FragmentVideoGridBinding
import kotlinx.coroutines.launch

class MyBangumiFollowFragment : Fragment() {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private val type: Int by lazy { requireArguments().getInt(ARG_TYPE) }
    private lateinit var adapter: BangumiFollowAdapter

    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false
    private var pendingRestorePosition: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter = BangumiFollowAdapter { position, season ->
                pendingRestorePosition = position
                val nav = parentFragment?.parentFragment as? MyNavigator
                nav?.openBangumiDetail(season.seasonId, isDrama = type == 2)
            }
        }
        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!binding.recycler.canScrollVertically(-1)) {
                                    val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
                                    val holder = binding.recycler.findContainingViewHolder(v) ?: return@setOnKeyListener false
                                    val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false
                                    val first = lm.findFirstVisibleItemPosition()
                                    if (first == pos) {
                                        focusSelectedMyTabIfAvailable()
                                        return@setOnKeyListener true
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_LEFT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    val switched = switchToPrevMyTabIfAvailable()
                                    return@setOnKeyListener switched
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_RIGHT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (switchToNextMyTabIfAvailable()) return@setOnKeyListener true
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_DOWN)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (!endReached) loadNextPage()
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - last - 1 <= 6) loadNextPage()
                }
            },
        )
        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
    }

    override fun onResume() {
        super.onResume()
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        resetAndLoad()
        initialLoadTriggered = true
    }

    private fun resetAndLoad() {
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nav = BiliApi.nav()
                val mid = nav.optJSONObject("data")?.optLong("mid") ?: 0L
                if (mid <= 0) error("invalid mid")

                val res = BiliApi.bangumiFollowList(vmid = mid, type = type, pn = page, ps = 15)
                if (token != requestToken) return@launch

                if (res.items.isEmpty()) {
                    endReached = true
                    return@launch
                }
                if (isRefresh) adapter.submit(res.items) else adapter.append(res.items)
                restoreFocusIfNeeded()
                page++
                if (res.pages > 0 && page > res.pages) endReached = true
            } catch (t: Throwable) {
                AppLog.e("MyBangumi", "load failed type=$type", t)
                Toast.makeText(requireContext(), "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                if (token == requestToken) binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun restoreFocusIfNeeded() {
        val pos = pendingRestorePosition ?: return
        if (_binding == null) return
        if (pos < 0 || pos >= adapter.itemCount) return
        binding.recycler.post {
            binding.recycler.scrollToPosition(pos)
            binding.recycler.post {
                binding.recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                pendingRestorePosition = null
            }
        }
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TYPE = "type"

        fun newInstance(type: Int): MyBangumiFollowFragment =
            MyBangumiFollowFragment().apply {
                arguments = Bundle().apply { putInt(ARG_TYPE, type) }
            }
    }
}
