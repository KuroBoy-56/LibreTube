package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentLibraryBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.PlaylistBookmarkAdapter
import com.github.libretube.ui.adapters.PlaylistsAdapter
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.dialogs.CreatePlaylistDialog
import com.github.libretube.ui.dialogs.CreatePlaylistDialog.Companion.CREATE_PLAYLIST_DIALOG_REQUEST_KEY
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : DynamicLayoutManagerFragment(R.layout.fragment_library) {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()

    private val playlistsAdapter = PlaylistsAdapter(PlaylistsHelper.getPrivatePlaylistType())
    private val playlistBookmarkAdapter = PlaylistBookmarkAdapter()
    private lateinit var customHistoryAdapter: HistoryCarouselAdapter

    private var allPlaylistsList: List<Playlists> = emptyList()
    // Guardamos la lista en memoria para el menú
    private var allBookmarksList: List<com.github.libretube.db.obj.PlaylistBookmark> = emptyList()

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.historyCarouselRv?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        _binding?.playlistRecView?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        _binding?.bookmarksRecView?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        customHistoryAdapter = HistoryCarouselAdapter(requireContext()) { streamItem ->
            NavigationHelper.navigateVideo(requireContext(), com.github.libretube.parcelable.PlayerData(videoId = streamItem.url))
        }

        binding.historyCarouselRv.adapter = customHistoryAdapter
        binding.playlistRecView.adapter = playlistsAdapter
        binding.bookmarksRecView.adapter = playlistBookmarkAdapter

        val density = requireContext().resources.displayMetrics.density
        val spacingPx = (12 * density).toInt()

        val spacingDecoration = object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                super.getItemOffsets(outRect, view, parent, state)
                if (parent.getChildAdapterPosition(view) != 0) {
                    outRect.left = spacingPx
                }
            }
        }
        binding.playlistRecView.addItemDecoration(spacingDecoration)
        binding.bookmarksRecView.addItemDecoration(spacingDecoration)

        playlistsAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                _binding?.nothingHere?.isVisible = playlistsAdapter.itemCount == 0
                super.onItemRangeRemoved(positionStart, itemCount)
            }
        })

        val watchHistoryEnabled = PreferenceHelper.getBoolean(PreferenceKeys.WATCH_HISTORY_TOGGLE, true)
        if (!watchHistoryEnabled) {
            binding.historyHeaderBtn.isGone = true
            binding.historyCarouselRv.isGone = true
        } else {
            binding.historyHeaderBtn.setOnClickListener {
                findNavController().navigate(R.id.action_libraryFragment_to_watchHistoryFragment)
            }
        }

        binding.playlistHeaderBtn.setOnClickListener {
            showAllPlaylistsModal()
        }

        // CONECTAMOS EL NUEVO BOTÓN DE "TUS VIDEOS"
        binding.bookmarksHeaderBtn.setOnClickListener {
            showAllBookmarksModal()
        }

        binding.downloadsBtn.setOnClickListener {
            findNavController().navigate(R.id.action_libraryFragment_to_downloadsFragment)
        }

        val navBarItems = NavBarHelper.getNavBarItems(requireContext())
        if (navBarItems.filter { it.isVisible }.any { it.itemId == R.id.downloadsFragment }) {
            binding.downloadsBtn.isGone = true
        }

        fetchHistoryPreview()
        fetchPlaylists()
        initBookmarks()

        binding.playlistRefresh.isEnabled = true
        binding.playlistRefresh.setOnRefreshListener {
            fetchHistoryPreview()
            fetchPlaylists()
            initBookmarks()
        }

        childFragmentManager.setFragmentResultListener(CREATE_PLAYLIST_DIALOG_REQUEST_KEY, this) { _, resultBundle ->
            val isPlaylistCreated = resultBundle.getBoolean(IntentData.playlistTask)
            if (isPlaylistCreated) {
                fetchPlaylists()
            }
        }
        binding.createPlaylistBtn.setOnClickListener {
            CreatePlaylistDialog().show(childFragmentManager, CreatePlaylistDialog::class.java.name)
        }
    }

    private fun fetchHistoryPreview() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyRecords = DatabaseHolder.Database.watchHistoryDao().getAll()
                // Invertimos para que lo más reciente esté primero y tomamos 20
                val recentHistory = historyRecords.asReversed().take(20).map { it.toStreamItem() }

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        if (recentHistory.isEmpty()) {
                            binding.historyCarouselRv.isGone = true
                        } else {
                            binding.historyCarouselRv.isVisible = true
                            customHistoryAdapter.submitList(recentHistory)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG(), "Error cargando historial: ${e.message}")
            }
        }
    }

    private fun initBookmarks() {
        lifecycleScope.launch {
            val bookmarks = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.playlistBookmarkDao().getAll()
            }
            val binding = _binding ?: return@launch

            allBookmarksList = bookmarks

            binding.bookmarksContainer.isVisible = bookmarks.isNotEmpty()
            if (bookmarks.isNotEmpty()) {
                playlistBookmarkAdapter.submitList(bookmarks)
            }
        }
    }

    private fun fetchPlaylists() {
        _binding?.playlistRefresh?.isRefreshing = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val playlists = try {
                    withContext(Dispatchers.IO) {
                        PlaylistsHelper.getPlaylists()
                    }
                } catch (e: Exception) {
                    Log.e(TAG(), e.toString())
                    Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                    return@repeatOnLifecycle
                }

                val binding = _binding ?: return@repeatOnLifecycle
                binding.playlistRefresh.isRefreshing = false

                allPlaylistsList = playlists

                if (playlists.isEmpty()) {
                    binding.nothingHere.isVisible = true
                    binding.playlistRecView.isGone = true
                } else {
                    binding.nothingHere.isGone = true
                    binding.playlistRecView.isVisible = true
                    playlistsAdapter.submitList(playlists)
                }
            }
        }
    }

    // --- MODAL DE PLAYLISTS ---
    private fun showAllPlaylistsModal() {
        if (allPlaylistsList.isEmpty()) {
            Toast.makeText(requireContext(), "No tienes playlists creadas", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheetDialog = BottomSheetDialog(requireActivity())

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 30)
        }

        val title = TextView(requireContext()).apply {
            text = "Tus Playlists"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(40, 16, 40, 30)
        }
        container.addView(title)

        val scrollView = android.widget.ScrollView(requireContext())
        val listLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        val selectableBackground = typedValue.resourceId

        val density = requireContext().resources.displayMetrics.density

        allPlaylistsList.forEach { playlist ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                setBackgroundResource(selectableBackground)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    bottomSheetDialog.dismiss()
                    findNavController().navigate(
                        R.id.playlistFragment,
                        bundleOf(
                            "playlistId" to playlist.id,
                            "playlistType" to PlaylistType.PRIVATE
                        )
                    )
                }
            }

            val avatar = ShapeableImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((120 * density).toInt(), (68 * density).toInt())
                val appearance = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.1f)).build()
                shapeAppearanceModel = appearance
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
            ImageHelper.loadImage(playlist.thumbnail, avatar, false)

            val name = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * density).toInt()
                }
                text = playlist.name
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            row.addView(avatar)
            row.addView(name)
            listLayout.addView(row)
        }

        scrollView.addView(listLayout)
        container.addView(scrollView)
        bottomSheetDialog.setContentView(container)
        bottomSheetDialog.show()
    }

    // --- NUEVO MODAL: TUS VIDEOS GUARDADOS (BOOKMARKS) ---
    private fun showAllBookmarksModal() {
        if (allBookmarksList.isEmpty()) {
            Toast.makeText(requireContext(), "No tienes videos guardados", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheetDialog = BottomSheetDialog(requireActivity())

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 30)
        }

        val title = TextView(requireContext()).apply {
            text = "Tus Videos"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(40, 16, 40, 30)
        }
        container.addView(title)

        val scrollView = android.widget.ScrollView(requireContext())
        val listLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        val selectableBackground = typedValue.resourceId

        val density = requireContext().resources.displayMetrics.density

        allBookmarksList.forEach { bookmark ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                setBackgroundResource(selectableBackground)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    bottomSheetDialog.dismiss()
                    findNavController().navigate(
                        R.id.playlistFragment,
                        bundleOf(
                            "playlistId" to bookmark.playlistId,
                            "playlistType" to PlaylistType.PUBLIC
                        )
                    )
                }
            }

            val avatar = ShapeableImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((120 * density).toInt(), (68 * density).toInt())
                val appearance = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.1f)).build()
                shapeAppearanceModel = appearance
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
            ImageHelper.loadImage(bookmark.thumbnailUrl, avatar, false)

            val name = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * density).toInt()
                }
                text = bookmark.playlistName
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            row.addView(avatar)
            row.addView(name)
            listLayout.addView(row)
        }

        scrollView.addView(listLayout)
        container.addView(scrollView)
        bottomSheetDialog.setContentView(container)
        bottomSheetDialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class HistoryCarouselAdapter(
        private val context: Context,
        private val onItemClick: (StreamItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items = listOf<StreamItem>()
        private val density = context.resources.displayMetrics.density

        private val TYPE_ITEM = 0
        private val TYPE_FOOTER = 1

        private fun dp(value: Int): Int = (value * density).toInt()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newItems: List<StreamItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (position < items.size) TYPE_ITEM else TYPE_FOOTER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_FOOTER) {
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.MarginLayoutParams(dp(160), dp(130)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                    }
                    isClickable = true
                    isFocusable = true
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                    setBackgroundResource(typedValue.resourceId)
                }

                val icon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    setImageResource(R.drawable.ic_history)
                    val colorOnSurface = android.util.TypedValue()
                    context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, colorOnSurface, true)
                    imageTintList = android.content.res.ColorStateList.valueOf(colorOnSurface.data)
                }

                val text = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    }
                    text = "Ver todo"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    val colorOnSurface = android.util.TypedValue()
                    context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, colorOnSurface, true)
                    setTextColor(colorOnSurface.data)
                }

                layout.addView(icon)
                layout.addView(text)

                return FooterViewHolder(layout)
            }

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            }

            val card = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(160), dp(90))
                radius = dp(8).toFloat()
                strokeWidth = 0
            }

            val frame = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

            val thumbnail = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val durationCard = MaterialCardView(context).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, dp(4), dp(4))
                }
                setCardBackgroundColor(Color.parseColor("#CC000000"))
                radius = dp(4).toFloat()
                strokeWidth = 0
            }

            val durationText = TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setTextColor(Color.WHITE)
                textSize = 12f
            }

            durationCard.addView(durationText)
            frame.addView(thumbnail)
            frame.addView(durationCard)
            card.addView(frame)

            val title = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                textSize = 14f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                val colorOnSurface = android.util.TypedValue()
                context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, colorOnSurface, true)
                setTextColor(colorOnSurface.data)
            }

            layout.addView(card)
            layout.addView(title)

            return HistoryViewHolder(layout, thumbnail, title, durationText, durationCard)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HistoryViewHolder) {
                val item = items[position]
                holder.title.text = item.title

                val durationValue = item.duration ?: 0L

                if (durationValue > 0L) {
                    holder.durationCard.isVisible = true
                    val minutes = durationValue / 60
                    val seconds = durationValue % 60
                    holder.duration.text = String.format("%02d:%02d", minutes, seconds)
                } else {
                    holder.durationCard.isGone = true
                }

                ImageHelper.loadImage(item.thumbnail, holder.thumbnail, false)
                holder.itemView.setOnClickListener { onItemClick(item) }
            } else if (holder is FooterViewHolder) {
                holder.itemView.setOnClickListener {
                    findNavController().navigate(R.id.action_libraryFragment_to_watchHistoryFragment)
                }
            }
        }

        override fun getItemCount() = items.size + 1

        inner class HistoryViewHolder(
            view: View,
            val thumbnail: ImageView,
            val title: TextView,
            val duration: TextView,
            val durationCard: MaterialCardView
        ) : RecyclerView.ViewHolder(view)

        inner class FooterViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}