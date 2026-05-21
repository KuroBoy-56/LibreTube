package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlaylistBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.adapters.PlaylistAdapter
import com.github.libretube.ui.adapters.PlaylistItem
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlaylistViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaylistFragment : DynamicLayoutManagerFragment(R.layout.fragment_playlist) {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<PlaylistFragmentArgs>()

    private lateinit var playlistId: String
    private var playlistName: String? = null
    private var playlistType = PlaylistType.PUBLIC

    private var playlistFeed = mutableListOf<StreamItem>()
    private var playlistAdapter: PlaylistAdapter? = null
    private var nextPage: String? = null
    private var isLoading = true
    private var isBookmarked = false

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val playlistViewModel: PlaylistViewModel by activityViewModels()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.PLAYLIST_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.PLAYLIST_SORT_ORDER, value)
            field = value
        }
    private val sortOptions by lazy { resources.getStringArray(R.array.playlistSortOptions) }
    private var recyclerViewState: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = args.playlistId
        playlistType = args.playlistType
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlaylistBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.playlistProgress.isVisible = true

        isBookmarked = runBlocking(Dispatchers.IO) {
            DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId)
        }
        updateBookmarkRes()

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.playlistRecView.updatePadding(bottom = if (it) 64f.dpToPx() else 0)
        }

        binding.playlistRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        fetchPlaylist()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateBookmarkRes() {
        binding.bookmark.setIconResource(
            if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
        )
    }

    private fun fetchPlaylist() {
        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    PlaylistsHelper.getPlaylist(playlistId)
                }
            } catch (e: Exception) {
                Log.e(TAG(), "Error al cargar la playlist: ${e.message}")
                withContext(Dispatchers.Main) {
                    _binding?.playlistProgress?.isGone = true
                    _binding?.nothingHere?.isVisible = true
                    context?.toastFromMainDispatcher(R.string.unknown_error)
                }
                return@launch
            }

            val binding = _binding ?: return@launch

            playlistFeed = response.relatedStreams.toMutableList()
            nextPage = response.nextpage
            playlistName = response.name
            isLoading = false

            if (!response.thumbnailUrl.isNullOrEmpty()) {
                ImageHelper.loadImage(response.thumbnailUrl, binding.thumbnail)
            } else {
                binding.thumbnail.setImageResource(R.drawable.ic_empty_playlist)
                binding.thumbnail.setPadding(64f.dpToPx())
                binding.thumbnail.setBackgroundColor(com.google.android.material.R.attr.colorSurface)
            }

            binding.playlistProgress.isGone = true
            binding.playlistAppBar.isVisible = true
            binding.playlistRecView.isVisible = true
            binding.playlistName.text = response.name

            binding.playlistInfo.text = getChannelAndVideoString(response, response.videos)
            binding.playlistInfo.setOnClickListener {
                NavigationHelper.navigateChannel(requireContext(), response.uploaderUrl)
            }

            binding.playlistDescription.text = response.description?.parseAsHtml()
            binding.playlistDescription.isGone = response.description.orEmpty().isBlank()

            playlistAdapter = PlaylistAdapter(playlistId) { streamItem ->
                startVideoItemPlayback(streamItem)
            }
            binding.playlistRecView.adapter = playlistAdapter

            playlistAdapter!!.registerAdapterDataObserver(object :
                RecyclerView.AdapterDataObserver() {
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    if (positionStart == 0) {
                        ImageHelper.loadImage(
                            playlistFeed.firstOrNull()?.thumbnail.orEmpty(),
                            binding.thumbnail
                        )
                    }

                    binding.playlistInfo.text = getChannelAndVideoString(response, playlistFeed.size)
                }
            })

            binding.playlistRecView.addOnBottomReachedListener {
                if (isLoading) return@addOnBottomReachedListener

                if (playlistType == PlaylistType.PUBLIC) {
                    fetchNextPage()
                }
            }

            if (playlistType != PlaylistType.PUBLIC) {
                binding.playlistRecView.setOnDismissListener { position ->
                    removeFromPlaylist(position)
                }
            }

            showPlaylistVideos()

            playlistViewModel.searchQuery.observe(viewLifecycleOwner) {
                showPlaylistVideos()
            }

            binding.optionsMenu.setOnClickListener {
                val sheet = PlaylistOptionsBottomSheet()
                sheet.arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName.orEmpty(),
                    IntentData.playlistType to playlistType
                )

                val fragmentManager = (context as BaseActivity).supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    PlaylistOptionsBottomSheet.PLAYLIST_OPTIONS_REQUEST_KEY,
                    (context as BaseActivity)
                ) { _, resultBundle ->
                    val newPlaylistDescription =
                        resultBundle.getString(IntentData.playlistDescription)
                    val newPlaylistName =
                        resultBundle.getString(IntentData.playlistName)
                    val isPlaylistToBeDeleted =
                        resultBundle.getBoolean(IntentData.playlistTask)

                    newPlaylistDescription?.let {
                        binding.playlistDescription.text = it
                        response.description = it
                    }

                    newPlaylistName?.let {
                        binding.playlistName.text = it
                        playlistName = it
                    }

                    if (isPlaylistToBeDeleted) {
                        findNavController().popBackStack()
                        return@setFragmentResultListener
                    }
                }

                sheet.show(fragmentManager)
            }

            if (playlistFeed.isEmpty()) {
                binding.nothingHere.isVisible = true
                binding.playAll.isGone = true
            } else {
                binding.playAll.setOnClickListener {
                    startVideoItemPlayback(getSortedVideos().first().item)
                }
            }

            if (playlistType == PlaylistType.PUBLIC) {
                binding.bookmark.setOnClickListener {
                    isBookmarked = !isBookmarked
                    updateBookmarkRes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!isBookmarked) {
                            DatabaseHolder.Database.playlistBookmarkDao()
                                .deleteById(playlistId)
                        } else {
                            DatabaseHolder.Database.playlistBookmarkDao()
                                .insert(response.toPlaylistBookmark(playlistId))
                        }
                    }
                }
            } else {
                if (playlistFeed.isEmpty()) {
                    binding.bookmark.isGone = true
                } else {
                    binding.bookmark.setIconResource(R.drawable.ic_shuffle)
                    binding.bookmark.text = getString(R.string.shuffle)
                    binding.bookmark.setOnClickListener {
                        val queue = playlistFeed.shuffled()
                        PlayingQueue.setStreams(queue)

                        navigateVideo(queue.firstOrNull() ?: return@setOnClickListener)
                    }
                }

                if (playlistFeed.isEmpty()) {
                    binding.sortBTN.isGone = true
                } else {
                    binding.sortBTN.isVisible = true
                    binding.sortBTN.setOnClickListener {
                        BaseBottomSheet().apply {
                            setSimpleItems(sortOptions.toList()) { index ->
                                selectedSortOrder = index
                                binding.sortBTN.text = sortOptions[index]
                                showPlaylistVideos()
                            }
                        }.show(childFragmentManager)
                    }
                }

                binding.sortBTN.text = sortOptions[selectedSortOrder]

            }

            updatePlaylistBookmark(response)
        }
    }

    private fun navigateVideo(streamItem: StreamItem) {
        NavigationHelper.navigateVideo(
            requireContext(),
            playerData = PlayerData(
                streamItem.url!!.toID(),
                playlistId = playlistId,
                keepQueue = true
            )
        )
    }

    private fun startVideoItemPlayback(streamItem: StreamItem) {
        if (playlistFeed.isEmpty()) return

        val sortedStreams = getSortedVideos()
        PlayingQueue.setStreams(sortedStreams.map { it.item })

        navigateVideo(streamItem)
    }

    private suspend fun updatePlaylistBookmark(playlist: Playlist) {
        if (!isBookmarked) return
        withContext(Dispatchers.IO) {
            val playlistBookmark =
                DatabaseHolder.Database.playlistBookmarkDao().findById(playlistId)
                    ?: return@withContext
            if (playlistBookmark.thumbnailUrl != playlist.thumbnailUrl ||
                playlistBookmark.playlistName != playlist.name ||
                playlistBookmark.videos != playlist.videos
            ) {
                DatabaseHolder.Database.playlistBookmarkDao()
                    .update(playlist.toPlaylistBookmark(playlistBookmark.playlistId))
            }
        }
    }

    private fun getSortedVideos(): List<PlaylistItem> {
        val items = playlistFeed.mapIndexed { index, item -> PlaylistItem(item, index) }

        return when {
            selectedSortOrder in listOf(0, 1) || playlistType == PlaylistType.PUBLIC -> {
                items
            }

            selectedSortOrder in listOf(2, 3) -> {
                items.sortedBy { it.item.duration }
            }

            selectedSortOrder in listOf(4, 5) -> {
                items.sortedBy { it.item.title }
            }

            else -> throw IllegalArgumentException()
        }.let {
            if (selectedSortOrder % 2 == 0) it else it.reversed()
        }
    }

    private fun showPlaylistVideos() {
        var videos = getSortedVideos()

        val query = playlistViewModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            videos = videos.filter { it.item.title.orEmpty().contains(query, ignoreCase = true) }
        }

        playlistAdapter?.submitList(videos)

        updatePlaylistDuration()
    }

    private fun removeFromPlaylist(sortedFeedPosition: Int) {
        val playlistAdapter = playlistAdapter ?: return

        val (video, originalPlaylistPosition) = playlistAdapter.currentList[sortedFeedPosition]

        val updatedList = playlistAdapter.currentList.toMutableList()
        updatedList.removeAt(sortedFeedPosition)
        val fixedList = fixItemIndices(updatedList, originalPlaylistPosition, -1)
        playlistAdapter.submitList(fixedList)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.removeFromPlaylist(playlistId, originalPlaylistPosition)

                val snackBarText = getString(R.string.successfully_removed_from_playlist)

                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, snackBarText, Snackbar.LENGTH_LONG)
                        .setTextMaxLines(3)
                        .setAction(R.string.undo) {
                            reAddToPlaylist(
                                video,
                                sortedFeedPosition,
                                originalPlaylistPosition
                            )
                        }
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    private fun reAddToPlaylist(
        streamItem: StreamItem,
        sortedFeedPosition: Int,
        originalFeedPosition: Int
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.addToPlaylist(playlistId, streamItem)

                val playlistAdapter = playlistAdapter ?: return@launch
                val updatedList = playlistAdapter.currentList.toMutableList()
                updatedList.add(sortedFeedPosition, PlaylistItem(streamItem, originalFeedPosition))
                val fixedList = fixItemIndices(updatedList, originalFeedPosition, +1)

                withContext(Dispatchers.Main) {
                    playlistAdapter.submitList(fixedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    private fun fixItemIndices(items: List<PlaylistItem>, modifiedPosition: Int, offset: Int): List<PlaylistItem> {
        return items.map {
            if (it.originalPlaylistIndex > modifiedPosition) {
                it.copy(originalPlaylistIndex = it.originalPlaylistIndex + offset)
            } else {
                it
            }
        }
    }

    // MAGIA 3: Evitamos el R.string.videoCount y armamos el texto manualmente. Cero crasheos.
    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    private fun getChannelAndVideoString(playlist: Playlist, count: Int): String {
        val uploader = playlist.uploader.orEmpty()
        if (count < 0) return uploader
        if (uploader.isEmpty()) return "$count videos"
        return "$uploader • $count videos"
    }

    private fun fetchNextPage() {
        if (nextPage == null || isLoading) return
        isLoading = true
        isLoading = false
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlaylistDuration() {
        val totalDuration = playlistFeed.sumOf { it.duration ?: 0 } ?: return
        binding.playlistDuration.text = DateUtils.formatElapsedTime(totalDuration) +
                if (nextPage != null) "+" else ""
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.playlistRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }
}