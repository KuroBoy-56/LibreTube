package com.github.libretube.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.ChannelTab
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentChannelBinding
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.ui.sheets.ChannelOptionsBottomSheet
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelFragment : Fragment(R.layout.fragment_channel) {
    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<ChannelFragmentArgs>()

    private var channelId: String? = null
    private var channelName: String? = null
    private var isLoading = true

    private lateinit var channelContentAdapter: ChannelContentAdapter

    private var nextPages = Array<String?>(5) { null }
    private var isAppBarFullyExpanded: Boolean = true
    private val tabList = mutableListOf<ChannelTab>()

    private val tabNamesMap = mapOf(
        VIDEOS_TAB_KEY to R.string.videos,
        "shorts" to R.string.yt_shorts,
        "playlists" to R.string.playlists,
        "albums" to R.string.albums
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelName = args.channelName
            ?.replace("/c/", "")
            ?.replace("/user/", "")
        channelId = args.channelId
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChannelBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        // Check if the AppBarLayout is fully expanded
        binding.channelAppBar.addOnOffsetChangedListener { _, verticalOffset ->
            isAppBarFullyExpanded = verticalOffset == 0
        }

        binding.pager.reduceDragSensitivity()

        // Determine if the child can scroll up
        binding.channelRefresh.setOnChildScrollUpCallback { _, _ ->
            !isAppBarFullyExpanded
        }

        binding.channelRefresh.setOnRefreshListener {
            fetchChannel()
        }

        fetchChannel()
    }

    // adjust sensitivity due to the issue of viewpager2 with SwipeToRefresh https://issuetracker.google.com/issues/138314213
    private fun ViewPager2.reduceDragSensitivity() {
        val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
        recyclerViewField.isAccessible = true
        val recyclerView = recyclerViewField.get(this) as RecyclerView

        val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
        touchSlopField.isAccessible = true
        val touchSlop = touchSlopField.get(recyclerView) as Int
        touchSlopField.set(recyclerView, touchSlop * 3)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun fetchChannel() = lifecycleScope.launch {
        isLoading = true
        _binding?.channelRefresh?.isRefreshing = true

        val response = try {
            withContext(Dispatchers.IO) {
                if (channelId != null) {
                    MediaServiceRepository.instance.getChannel(channelId!!)
                } else {
                    MediaServiceRepository.instance.getChannelByName(channelName!!)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
            return@launch
        } finally {
            _binding?.channelRefresh?.isRefreshing = false
            isLoading = false
        }
        val binding = _binding ?: return@launch

        // needed if the channel gets loaded by the ID
        channelId = response.id
        channelName = response.name

        val channelId = channelId ?: return@launch

        var isSubscribed = false
        binding.channelSubscribe.setupSubscriptionButton(
            channelId,
            response.name.orEmpty(),
            response.avatarUrl,
            response.verified,
            binding.notificationBell
        ) {
            isSubscribed = it
        }

        binding.showMore.setOnClickListener {
            ChannelOptionsBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.channelId to channelId,
                        IntentData.channelName to channelName,
                        IntentData.isSubscribed to isSubscribed
                    )
                }
                .show(childFragmentManager)
        }

        nextPages[0] = response.nextpage
        isLoading = false
        binding.channelRefresh.isRefreshing = false

        binding.channelCoordinator.isVisible = true

        binding.channelName.text = response.name
        binding.channelName.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = response.name.orEmpty())
            true
        }

        if (response.verified) {
            binding.channelName
                .setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_verified, 0)
        }
        binding.channelSubs.text = resources.getString(
            R.string.subscribers,
            response.subscriberCount.formatShort()
        )
        if (response.description.orEmpty().isBlank()) {
            binding.channelDescription.isGone = true
        } else {
            binding.channelDescription.text = response.description.orEmpty().trim()
        }

        ImageHelper.loadImage(response.bannerUrl, binding.channelBanner)
        ImageHelper.loadImage(response.avatarUrl, binding.channelImage, true)

        binding.channelImage.setOnClickListener {
            NavigationHelper.openImagePreview(
                requireContext(),
                response.avatarUrl ?: return@setOnClickListener
            )
        }

        binding.channelBanner.setOnClickListener {
            NavigationHelper.openImagePreview(
                requireContext(),
                response.bannerUrl ?: return@setOnClickListener
            )
        }

        // --- MANEJO ROBUSTO DE PESTAÑAS ---
        tabList.clear()
        val serverTabs = response.tabs
        
        var finalStreams = response.relatedStreams
        
        // --- FALLBACK DE BÚSQUEDA SI NO HAY VÍDEOS ---
        if (finalStreams.isEmpty()) {
            try {
                val searchQuery = response.name ?: channelName ?: ""
                if (searchQuery.isNotEmpty()) {
                    val searchResult = withContext(Dispatchers.IO) {
                        MediaServiceRepository.instance.getSearchResults(searchQuery, "videos")
                    }
                    finalStreams = searchResult.items
                        .filter { it.type == "stream" }
                        .map { it.toStreamItem() }
                        .sortedByDescending { it.uploaded } // Ordenar por fecha
                }
            } catch (e: Exception) {
                // Silently fail search fallback
            }
        } else {
            // Ordenar streams normales por fecha también
            finalStreams = finalStreams.sortedByDescending { it.uploaded }
        }

        // Identificar la pestaña de videos del servidor (si existe)
        val serverVideosTab = serverTabs.find { 
            it.name.equals("videos", ignoreCase = true) || 
            it.name.equals(VIDEOS_TAB_KEY, ignoreCase = true) ||
            it.data.contains("videos", ignoreCase = true) 
        }
        
        // Asegurar que la pestaña de Vídeos sea siempre la primera
        val videosTabName = getString(R.string.videos)
        // Usar los datos de la pestaña del servidor si existen, sino usar "" para fallback a relatedStreams
        val videosTabData = serverVideosTab?.data ?: ""
        tabList.add(ChannelTab(videosTabName, videosTabData))

        // Agregar el resto de pestañas (EXCLUYENDO directos)
        for (serverTab in serverTabs) {
            // Saltamos la de videos porque ya la agregamos
            if (serverTab == serverVideosTab) continue
            
            // Saltamos explícitamente cualquier pestaña de directos/live
            if (serverTab.name.equals("livestreams", ignoreCase = true) ||
                serverTab.name.equals("live", ignoreCase = true) ||
                serverTab.data.contains("live", ignoreCase = true)) {
                continue
            }
            
            val tabName = tabNamesMap[serverTab.name.lowercase()]?.let { getString(it) }
                ?: serverTab.name.replaceFirstChar(Char::titlecase)
            
            tabList.add(ChannelTab(tabName, serverTab.data))
        }

        channelContentAdapter = ChannelContentAdapter(
            tabList,
            finalStreams,
            response.nextpage,
            channelId,
            response.name ?: channelName,
            this@ChannelFragment
        )
        binding.pager.adapter = channelContentAdapter
        TabLayoutMediator(binding.tabParent, binding.pager) { tab, position ->
            tab.text = tabList[position].name
        }.attach()
    }

    companion object {
        private const val VIDEOS_TAB_KEY = "videos"
    }
}

class ChannelContentAdapter(
    private val list: List<ChannelTab>,
    private val videos: List<StreamItem>,
    private val nextPage: String?,
    private val channelId: String?,
    private val channelName: String?,
    fragment: Fragment
) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = list.size

    override fun createFragment(position: Int) = ChannelContentFragment().apply {
        arguments = bundleOf(
            IntentData.tabData to list[position],
            IntentData.videoList to videos.toMutableList(),
            IntentData.channelId to channelId,
            IntentData.channelName to channelName,
            IntentData.nextPage to nextPage
        )
    }
}
