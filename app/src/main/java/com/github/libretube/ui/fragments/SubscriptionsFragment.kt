package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.databinding.FragmentSubscriptionsBinding
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsFragment : DynamicLayoutManagerFragment(R.layout.fragment_subscriptions) {
    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SubscriptionsViewModel by activityViewModels()

    private var isAppBarFullyExpanded = true
    private var feedAdapter = VideoCardsAdapter()

    // --- ESTADO DEL CARRUSEL ---
    private lateinit var carouselAdapter: ChannelCarouselAdapter
    private var allChannelsList: List<CarouselChannel> = emptyList()

    private var hideWatched = PreferenceHelper.getBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, false)
    private var showUpcoming = PreferenceHelper.getBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, true)

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.subFeed?.layoutManager = LinearLayoutManager(requireContext())
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSubscriptionsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.subFeed.adapter = feedAdapter

        carouselAdapter = ChannelCarouselAdapter { channel ->
            if (channel.isViewAllBtn) {
                showAllChannelsModal()
            } else {
                NavigationHelper.navigateChannel(requireContext(), channel.url)
            }
        }
        binding.channelCarouselRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.channelCarouselRv.adapter = carouselAdapter

        binding.subscriptionsAppBar.addOnOffsetChangedListener { _, verticalOffset ->
            isAppBarFullyExpanded = verticalOffset == 0
        }

        binding.subRefresh.setOnChildScrollUpCallback { _, _ ->
            !isAppBarFullyExpanded || binding.subFeed.canScrollVertically(-1)
        }

        binding.subRefresh.isEnabled = true
        binding.subProgress.isVisible = true

        if (viewModel.videoFeed.value == null) {
            viewModel.fetchFeed(requireContext(), forceRefresh = false)
        }

        if (viewModel.subscriptions.value == null) {
            viewModel.fetchSubscriptions(requireContext())
        }

        viewModel.subscriptions.observe(viewLifecycleOwner) { subscriptions ->
            if (!subscriptions.isNullOrEmpty()) {
                extractChannelsForCarousel(subscriptions)
                // Solo cargar feed si la lista de videos está totalmente vacía
                if (viewModel.videoFeed.value.isNullOrEmpty()) {
                    viewModel.fetchFeed(requireContext(), forceRefresh = false)
                }
            }
        }

        var alreadyShowedFeedOnce = false
        viewModel.videoFeed.observe(viewLifecycleOwner) { feed ->
            if (feed != null) {
                lifecycleScope.launch {
                    showFeed(!alreadyShowedFeedOnce)
                }
                alreadyShowedFeedOnce = true
            }

            feed?.firstOrNull { !it.isUpcoming }?.uploaded?.let {
                PreferenceHelper.updateLastFeedWatchedTime(it, true)
            }
        }

        viewModel.feedProgress.observe(viewLifecycleOwner) { progress ->
            if (progress == null || progress.currentProgress == progress.total) {
                binding.feedProgressContainer.animate()
                    .alpha(0.5f)
                    .scaleY(0.5f)
                    .withEndAction {
                        val bind = _binding ?: return@withEndAction
                        bind.feedProgressContainer.isGone = true
                        bind.feedProgressContainer.scaleY = 1f
                        bind.feedProgressContainer.alpha = 1f
                    }
                    .setDuration(200)
                    .start()
            } else {
                binding.feedProgressContainer.isVisible = true
                binding.feedProgressText.text = "${progress.currentProgress}/${progress.total}"
                binding.feedProgressBar.max = progress.total
                binding.feedProgressBar.progress = progress.currentProgress
            }
        }

        binding.subRefresh.setOnRefreshListener {
            viewModel.fetchFeed(requireContext(), forceRefresh = true)
            viewModel.fetchSubscriptions(requireContext())
        }

        binding.subFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                viewModel.subFeedRecyclerViewState =
                    recyclerView.layoutManager?.onSaveInstanceState()?.takeIf {
                        recyclerView.computeVerticalScrollOffset() != 0
                    }
            }
        })
    }

    private fun extractChannelsForCarousel(subscriptions: List<Subscription>) {
        val uniqueChannels = subscriptions.distinctBy { it.url }.map {
            CarouselChannel(
                name = it.name,
                url = it.url,
                avatarUrl = it.avatar,
                isViewAllBtn = false
            )
        }

        allChannelsList = uniqueChannels
        val maxChannels = if (uniqueChannels.size > 15) 15 else uniqueChannels.size
        val displayList = uniqueChannels.take(maxChannels).toMutableList()

        displayList.add(CarouselChannel("Ver todos", "", null, true))
        carouselAdapter.submitList(displayList)
    }

    private fun showAllChannelsModal() {
        val bottomSheetDialog = BottomSheetDialog(requireActivity())

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 30, 0, 30) // Más compacto arriba y abajo
        }

        val title = TextView(requireContext()).apply {
            text = "Todas las suscripciones"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(40, 16, 40, 30) // Más compacto
        }
        container.addView(title)

        val scrollView = android.widget.ScrollView(requireContext())
        val listLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val outValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        val selectableBackground = outValue.resourceId

        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        val borderlessBackground = outValue.resourceId

        allChannelsList.forEach { channel ->
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(40, 16, 40, 16) // Padding vertical reducido para unir más las opciones
                setBackgroundResource(selectableBackground)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    bottomSheetDialog.dismiss()
                    NavigationHelper.navigateChannel(requireContext(), channel.url)
                }
            }

            val avatar = ShapeableImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(100, 100) // Tamaño reducido a 100
                val appearance = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.5f)).build()
                shapeAppearanceModel = appearance
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
            ImageHelper.loadImage(channel.avatarUrl, avatar, true)

            val name = TextView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 32 // Más pegado a la imagen
                }
                text = channel.name
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val bellBtn = ImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(80, 80)
                setImageResource(R.drawable.ic_notifications)
                setBackgroundResource(borderlessBackground)
                setPadding(12, 12, 12, 12)
                isClickable = true
                isFocusable = true
                tag = true
                setOnClickListener {
                    val isActivated = tag as Boolean
                    tag = !isActivated
                    alpha = if (!isActivated) 1.0f else 0.4f
                    val msg = if (!isActivated) "Notificaciones activadas para ${channel.name}" else "Notificaciones desactivadas para ${channel.name}"
                    Snackbar.make(container, msg, Snackbar.LENGTH_SHORT).show()
                }
            }

            row.addView(avatar)
            row.addView(name)
            row.addView(bellBtn)
            listLayout.addView(row)
        }

        scrollView.addView(listLayout)
        container.addView(scrollView)
        bottomSheetDialog.setContentView(container)
        bottomSheetDialog.show()
    }

    private suspend fun showFeed(restoreScrollState: Boolean = true) {
        val binding = _binding ?: return
        val videoFeed = viewModel.videoFeed.value ?: return

        val feed = DatabaseHelper.filterByStreamTypeAndWatchPosition(videoFeed, hideWatched, showUpcoming)

        binding.subProgress.isGone = true

        val notLoaded = videoFeed.isEmpty()
        binding.subFeed.isGone = notLoaded && feed.isEmpty()
        binding.emptyFeed.isVisible = notLoaded || feed.isEmpty()

        binding.subRefresh.isRefreshing = false

        feedAdapter.submitList(feed) {
            if (restoreScrollState) {
                binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
            } else {
                binding.subFeed.scrollToPosition(0)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun removeItem(videoId: String) {
        feedAdapter.removeItemById(videoId)
    }
}

// --- ADAPTADOR INTERNO PARA EL CARRUSEL ---
data class CarouselChannel(
    val name: String,
    val url: String,
    val avatarUrl: String?,
    val isViewAllBtn: Boolean
)

class ChannelCarouselAdapter(private val onChannelClick: (CarouselChannel) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = listOf<CarouselChannel>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newItems: List<CarouselChannel>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isViewAllBtn) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 1) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.filter_chip, parent, false)
            ViewAllViewHolder(view)
        } else {
            val layout = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6, 0, 6, 0) } // Margen más pequeño
            }

            val imageView = ShapeableImageView(parent.context).apply {
                id = View.generateViewId()
                layoutParams = android.widget.LinearLayout.LayoutParams(100, 100) // Tamaño reducido a 100
                val appearance = com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.5f)).build()
                shapeAppearanceModel = appearance
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }

            val textView = TextView(parent.context).apply {
                id = View.generateViewId()
                layoutParams = android.widget.LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 4 // Más pegado a la imagen
                }
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            layout.addView(imageView)
            layout.addView(textView)
            ChannelViewHolder(layout, imageView, textView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is ChannelViewHolder) {
            holder.nameView.text = item.name
            ImageHelper.loadImage(item.avatarUrl, holder.imageView, true)
            holder.itemView.setOnClickListener { onChannelClick(item) }
        } else if (holder is ViewAllViewHolder) {
            (holder.itemView as? com.google.android.material.chip.Chip)?.text = "Ver todos \u2192"
            holder.itemView.setOnClickListener { onChannelClick(item) }
        }
    }

    override fun getItemCount() = items.size

    class ChannelViewHolder(view: View, val imageView: ShapeableImageView, val nameView: TextView) : RecyclerView.ViewHolder(view)
    class ViewAllViewHolder(view: View) : RecyclerView.ViewHolder(view)
}