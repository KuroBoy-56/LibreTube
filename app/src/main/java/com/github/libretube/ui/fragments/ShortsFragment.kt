package com.github.libretube.ui.fragments

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.ShortItem
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Streams
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.compat.PictureInPictureParamsCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentShortsBinding
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ShortsMediaSources
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.adapters.ShortsPagerAdapter
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.ShortsViewModel
import com.github.libretube.ui.sheets.CommentsSheet
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sección Shorts, como la app de YouTube: un Short vertical a pantalla completa; deslizar hacia
 * arriba pasa al siguiente y hacia abajo vuelve al anterior. Tocar pausa o sigue. Al terminar se
 * repite. A la derecha me gusta, no me gusta, comentarios, compartir y el canal; abajo el canal con
 * Suscribirse y el título.
 *
 * El feed es el de la pestaña Shorts de YouTube (la misma lógica que la app de Roku, ver
 * [com.github.libretube.api.ShortsRepository]). Un solo reproductor pasa a la página que se ve;
 * los datos de los dos Shorts siguientes se cargan antes, así al deslizar empiezan al momento.
 */
@OptIn(UnstableApi::class)
class ShortsFragment : Fragment(R.layout.fragment_shorts) {
    private var _binding: FragmentShortsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShortsViewModel by activityViewModels()
    private lateinit var adapter: ShortsPagerAdapter

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentPosition = RecyclerView.NO_POSITION
    private var playJob: Job? = null
    private var progressJob: Job? = null
    private val streamsRequests = LinkedHashMap<String, Deferred<Streams>>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentShortsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        adapter = ShortsPagerAdapter { holder, item, details -> bindPage(holder, item, details) }
        binding.shortsPager.adapter = adapter
        binding.shortsPager.offscreenPageLimit = 1
        binding.shortsPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                playAt(position)
                viewModel.loadMoreIfNeeded(position)
            }
        })

        viewModel.shorts.observe(viewLifecycleOwner) { list ->
            binding.shortsLoading.isVisible = list.isEmpty() && viewModel.error.value == null
            adapter.submitList(list) {
                if (_binding == null) return@submitList
                val start = viewModel.pendingStartIndex
                if (start != null && start < list.size) {
                    viewModel.pendingStartIndex = null
                    if (binding.shortsPager.currentItem == start) {
                        if (currentPosition != start) playAt(start, force = true)
                    } else {
                        // onPageSelected pone el Short.
                        binding.shortsPager.setCurrentItem(start, false)
                    }
                } else if (currentPosition == RecyclerView.NO_POSITION && list.isNotEmpty()) {
                    playAt(binding.shortsPager.currentItem)
                }
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            val empty = viewModel.shorts.value.isNullOrEmpty()
            binding.shortsError.isVisible = error != null && empty
            if (error != null && empty) binding.shortsLoading.isVisible = false
        }
        binding.shortsRetry.setOnClickListener {
            binding.shortsError.isVisible = false
            binding.shortsLoading.isVisible = true
            viewModel.retry()
        }

        // Abajo, el Short termina donde empieza la barra de Inicio/Shorts/...: el canal, Suscribirse
        // y los botones nunca quedan detrás de ella.
        activity?.findViewById<View>(R.id.bottomNav)?.addOnLayoutChangeListener(bottomBarListener)
        view.addOnLayoutChangeListener(bottomBarListener)
        view.post { fitAboveBottomBar() }

        setupComments()

        viewModel.ensureFeed()
    }

    override fun onResume() {
        super.onResume()
        // Como la app de YouTube: el video principal se pausa mientras se ven Shorts, y su mini
        // reproductor no tapa el Short.
        (activity as? MainActivity)?.runOnPlayerFragment { pauseForShorts(); true }
        (activity as? MainActivity)?.runOnAudioPlayerFragment { pauseForShorts(); true }
        (activity as? MainActivity)?.setMiniPlayerHiddenForShorts(true)
        // Pantalla completa: arriba no queda nada (la barra de estado se ve deslizando desde arriba).
        setStatusBarHidden(true)
        view?.post { fitAboveBottomBar() }
        if (currentPosition != RecyclerView.NO_POSITION) player?.play()
        updatePictureInPictureParams()
    }

    override fun onPause() {
        super.onPause()
        // En imagen en imagen el Short sigue sonando en su ventana.
        if (isInPictureInPicture()) return
        player?.pause()
        setStatusBarHidden(false)
        // Fuera de Shorts no se entra solo en imagen en imagen con un Short.
        updatePictureInPictureParams(force = true)
        (activity as? MainActivity)?.setMiniPlayerHiddenForShorts(false)
    }

    override fun onStop() {
        super.onStop()
        // La ventana de imagen en imagen se cerró (o la app quedó atrás): el Short se detiene.
        if (!isInPictureInPicture()) player?.pause()
    }

    // ---------------------------------------------------------------------------------------
    // Comentarios (como la app de YouTube): el Short se achica arriba, sigue sonando completo y
    // abajo se abren los comentarios con la misma lista y respuestas que el reproductor.
    // ---------------------------------------------------------------------------------------

    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private var commentsOpen = false
    private var commentsAnimator: ValueAnimator? = null

    /** 0 = cerrados, 1 = abiertos (también mientras se arrastran). */
    private var commentsProgress = 0f

    /** Atrás: primero sale de las respuestas, después cierra los comentarios. */
    private val commentsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStack()
            } else {
                closeComments()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupComments() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, commentsBackCallback)
        binding.shortsCommentsClose.setOnClickListener { closeComments() }

        commentsViewModel.commentCountLiveData.observe(viewLifecycleOwner) { count ->
            _binding?.shortsCommentsCount?.text = count?.formatShort().orEmpty()
        }

        // Lo que piden la lista y las respuestas (los mismos avisos que en el reproductor).
        childFragmentManager.setFragmentResultListener(
            CommentsSheet.DISMISS_SHEET_REQUEST_KEY, viewLifecycleOwner
        ) { _, _ -> closeComments(animated = false) }
        childFragmentManager.setFragmentResultListener(
            CommentsSheet.HANDLE_LINK_REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString(IntentData.url)?.let {
                IntentHelper.openLinkFromHref(requireContext(), childFragmentManager, it)
            }
        }

        // Arrastrar el título hacia abajo cierra los comentarios (el video crece con el dedo).
        var startY = 0f
        binding.shortsCommentsHeader.setOnTouchListener { _, event ->
            val panel = _binding?.shortsCommentsPanel ?: return@setOnTouchListener false
            if (!commentsOpen || panel.height == 0) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    commentsAnimator?.cancel()
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - startY).coerceAtLeast(0f)
                    applyCommentsProgress(1f - dy / panel.height)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (commentsProgress < 0.75f) closeComments() else animateComments(1f)
                    true
                }
                else -> false
            }
        }
    }

    /** Abre los comentarios del Short que se está viendo. */
    private fun openComments(item: ShortItem, details: Streams?) {
        val b = _binding ?: return
        if (commentsOpen) return
        commentsOpen = true
        commentsBackCallback.isEnabled = true

        // La lista de este Short (la misma que usa el reproductor, empieza desde arriba).
        commentsViewModel.reset()
        commentsViewModel.videoIdLiveData.value = item.videoId
        b.shortsCommentsCount.text = ""
        childFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        childFragmentManager.commit {
            replace<CommentsMainFragment>(
                R.id.commentFragContainer,
                args = bundleOf(IntentData.channelAvatar to details?.uploaderAvatar)
            )
        }

        // Mientras están abiertos: no se pasa a otro Short y solo se ve el video.
        b.shortsPager.isUserInputEnabled = false
        currentHolder()?.let {
            applyPictureInPictureLayout(it)
            it.binding.shortPlayIcon.isVisible = false
        }
        updateResizeMode()

        // Sin la barra de abajo, como la app; se anima cuando la pantalla ya midió ese espacio.
        (activity as? MainActivity)?.setBottomBarHiddenForShorts(true)
        b.root.requestLayout()
        b.root.doOnNextLayout { if (commentsOpen) animateComments(1f) }
    }

    /** Cierra los comentarios: el Short vuelve a pantalla completa. */
    private fun closeComments(animated: Boolean = true) {
        if (!commentsOpen) return
        commentsOpen = false
        commentsBackCallback.isEnabled = false
        if (animated && _binding != null) {
            animateComments(0f)
        } else {
            commentsAnimator?.cancel()
            applyCommentsProgress(0f)
            onCommentsClosed()
        }
    }

    private fun onCommentsClosed() {
        val b = _binding ?: return
        b.shortsCommentsPanel.isVisible = false
        b.shortsPager.layoutParams = b.shortsPager.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        b.shortsPager.isUserInputEnabled = true
        childFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        childFragmentManager.findFragmentById(R.id.commentFragContainer)?.let {
            childFragmentManager.commit { remove(it) }
        }
        currentHolder()?.let { applyPictureInPictureLayout(it) }
        updateResizeMode()
        if (!isInPictureInPicture()) {
            (activity as? MainActivity)?.setBottomBarHiddenForShorts(false)
        }
        b.root.post { fitAboveBottomBar() }
    }

    /** Anima los comentarios hasta [target] (1 = abiertos, 0 = cerrados). */
    private fun animateComments(target: Float) {
        val b = _binding ?: return
        commentsAnimator?.cancel()
        b.shortsCommentsPanel.isVisible = true
        val from = commentsProgress
        commentsAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = (COMMENTS_ANIMATION_MS * kotlin.math.abs(target - from)).toLong().coerceAtLeast(1L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyCommentsProgress(it.animatedValue as Float) }
            doOnEnd {
                if (target == 0f && !commentsOpen) onCommentsClosed()
            }
            start()
        }
    }

    /**
     * Pone el video y el panel según [progress]: el panel ocupa el 62 % de abajo y el video lo que
     * queda arriba.
     */
    private fun applyCommentsProgress(progress: Float) {
        val b = _binding ?: return
        val p = progress.coerceIn(0f, 1f)
        commentsProgress = p
        val total = b.root.height - b.root.paddingBottom
        if (total <= 0) return
        val panelHeight = (total * COMMENTS_HEIGHT_FRACTION).toInt()
        val panelParams = b.shortsCommentsPanel.layoutParams
        if (panelParams.height != panelHeight) {
            panelParams.height = panelHeight
            b.shortsCommentsPanel.layoutParams = panelParams
        }
        b.shortsCommentsPanel.isVisible = p > 0f
        b.shortsCommentsPanel.translationY = panelHeight * (1f - p)
        val pagerParams = b.shortsPager.layoutParams
        pagerParams.height = if (p <= 0f) ViewGroup.LayoutParams.MATCH_PARENT else (total - panelHeight * p).toInt()
        b.shortsPager.layoutParams = pagerParams
    }

    /** Un Short vertical llena la pantalla; uno horizontal, o con los comentarios, se ve completo. */
    private fun updateResizeMode() {
        val size = player?.videoSize
        val vertical = size != null && size.height >= size.width
        playerView?.resizeMode = if (vertical && !commentsOpen) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun isInPictureInPicture(): Boolean =
        activity?.let { PictureInPictureCompat.isInPictureInPictureMode(it) } == true

    /**
     * Imagen en imagen vertical, como la app de YouTube: mientras un Short suena, al salir con
     * Home sigue en una ventana con la forma del video (9:16). En Android 12 o más entra solo; en
     * versiones anteriores lo hace [onUserLeaveHint].
     */
    private fun pictureInPictureParams(): PictureInPictureParamsCompat {
        val playing = player?.isPlaying == true
        val builder = PictureInPictureParamsCompat.Builder()
            .setAutoEnterEnabled(PlayerHelper.pipEnabled && playing && isResumed)
        val size = player?.videoSize
        if (size != null && size.width > 0 && size.height > 0) {
            builder.setAspectRatio(size)
        } else {
            builder.setAspectRatio(Rational(9, 16))
        }
        playerView?.let { videoView ->
            val rect = Rect()
            if (videoView.isAttachedToWindow && videoView.getGlobalVisibleRect(rect)) builder.setSourceRectHint(rect)
        }
        return builder.build()
    }

    /**
     * Pone los parámetros del imagen en imagen de Shorts. Solo mientras la ventana es de Shorts,
     * salvo [force] (al salir de Shorts, para apagar la entrada automática).
     */
    private fun updatePictureInPictureParams(force: Boolean = false) {
        val activity = activity ?: return
        if (!PictureInPictureCompat.isPictureInPictureAvailable(activity)) return
        if (!force && (activity as? MainActivity)?.shortsOwnPictureInPicture != true) return
        runCatching { PictureInPictureCompat.setPictureInPictureParams(activity, pictureInPictureParams()) }
    }

    /**
     * Salida con Home (la llama MainActivity): si un Short está sonando, entra en imagen en
     * imagen. Devuelve true si lo hizo.
     */
    fun onUserLeaveHint(): Boolean {
        val activity = activity ?: return false
        if (!isResumed || player?.isPlaying != true || !PlayerHelper.pipEnabled) return false
        if (!PictureInPictureCompat.isPictureInPictureAvailable(activity)) return false
        if ((activity as? MainActivity)?.shortsOwnPictureInPicture != true) return false
        return runCatching {
            PictureInPictureCompat.enterPictureInPictureMode(activity, pictureInPictureParams())
            true
        }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        val main = activity as? MainActivity
        // En la ventana solo el video: sin botones, textos ni la barra de abajo.
        main?.setBottomBarHiddenForShorts(isInPictureInPictureMode)
        if (isInPictureInPictureMode && commentsOpen) closeComments(animated = false)
        currentHolder()?.let { applyPictureInPictureLayout(it) }
        if (!isInPictureInPictureMode) {
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                // Se cerró la ventana: el Short se detiene y la app queda como antes.
                player?.pause()
                updatePictureInPictureParams(force = true)
                main?.setMiniPlayerHiddenForShorts(false)
                setStatusBarHidden(false)
            }
            view?.post { fitAboveBottomBar() }
        } else {
            _binding?.root?.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * Oculta (en imagen en imagen o con los comentarios abiertos) o muestra lo que va encima del
     * video de una página.
     */
    private fun applyPictureInPictureLayout(holder: ShortsPagerAdapter.PageViewHolder) {
        val show = !isInPictureInPicture() && !commentsOpen
        with(holder.binding) {
            shortActions.isVisible = show
            shortInfo.isVisible = show
            shortGradient.isVisible = show
            shortProgress.isVisible = show
            if (!show) shortPlayIcon.isVisible = false
        }
    }

    private val bottomBarListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        fitAboveBottomBar()
    }

    /**
     * Deja abajo el espacio que ocupa la barra de Inicio/Shorts/... sobre la pantalla de Shorts
     * (según dónde está cada una en la pantalla), así nada del Short queda detrás de ella.
     */
    private fun fitAboveBottomBar() {
        val root = _binding?.root ?: return
        val bottomBar = activity?.findViewById<View>(R.id.bottomNav)
        var overlap = 0
        if (bottomBar != null && bottomBar.isShown && bottomBar.height > 0) {
            val rootLocation = IntArray(2)
            val barLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            bottomBar.getLocationOnScreen(barLocation)
            val barTop = barLocation[1] + bottomBar.translationY.toInt().coerceAtMost(0)
            overlap = (rootLocation[1] + root.height - barTop).coerceIn(0, root.height / 3)
        }
        if (root.paddingBottom != overlap) root.setPadding(0, 0, 0, overlap)
    }

    private fun setStatusBarHidden(hidden: Boolean) {
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onDestroyView() {
        commentsAnimator?.cancel()
        if (commentsOpen && !isInPictureInPicture()) {
            (activity as? MainActivity)?.setBottomBarHiddenForShorts(false)
        }
        commentsOpen = false
        activity?.findViewById<View>(R.id.bottomNav)?.removeOnLayoutChangeListener(bottomBarListener)
        view?.removeOnLayoutChangeListener(bottomBarListener)
        playJob?.cancel()
        progressJob?.cancel()
        streamsRequests.values.forEach { it.cancel() }
        streamsRequests.clear()
        (playerView?.parent as? ViewGroup)?.removeView(playerView)
        playerView?.player = null
        playerView = null
        player?.release()
        player = null
        currentPosition = RecyclerView.NO_POSITION
        super.onDestroyView()
        _binding = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val context = requireContext()
        val newPlayer = PlayerHelper.createPlayer(context, DefaultTrackSelector(context)).apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    currentHolder()?.binding?.shortBuffering?.isVisible =
                        playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) {
                        currentHolder()?.binding?.shortThumbnail?.isVisible = false
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) currentHolder()?.binding?.shortPlayIcon?.isVisible = false
                    updatePictureInPictureParams()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // Un Short vertical llena la pantalla; uno horizontal se ve completo.
                    updateResizeMode()
                    updatePictureInPictureParams()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "playback", error)
                    context.let {
                        Toast.makeText(it, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        player = newPlayer
        playerView = PlayerView(context).apply {
            useController = false
            setShutterBackgroundColor(Color.TRANSPARENT)
            setKeepContentOnPlayerReset(false)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            keepScreenOn = true
            player = newPlayer
        }
        return newPlayer
    }

    private fun currentHolder(): ShortsPagerAdapter.PageViewHolder? {
        val recycler = _binding?.shortsPager?.getChildAt(0) as? RecyclerView ?: return null
        return recycler.findViewHolderForAdapterPosition(currentPosition) as? ShortsPagerAdapter.PageViewHolder
    }

    /** Pone el reproductor en la página [position] y reproduce su Short. */
    private fun playAt(position: Int, force: Boolean = false) {
        val list = viewModel.shorts.value.orEmpty()
        if (position !in list.indices || _binding == null) return
        if (position == currentPosition && !force) return
        currentPosition = position
        val item = list[position]

        val recycler = binding.shortsPager.getChildAt(0) as? RecyclerView
        val holder = recycler?.findViewHolderForAdapterPosition(position) as? ShortsPagerAdapter.PageViewHolder
        if (holder == null) {
            // La página aún no está armada: se reintenta en el próximo cuadro.
            binding.shortsPager.post { if (currentPosition == position) playAt(position, force = true) }
            return
        }

        val player = ensurePlayer()
        player.stop()
        player.clearMediaItems()
        (playerView?.parent as? ViewGroup)?.removeView(playerView)
        holder.binding.shortPlayerSlot.addView(
            playerView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        holder.binding.shortThumbnail.isVisible = true
        holder.binding.shortPlayIcon.isVisible = false
        holder.binding.shortBuffering.isVisible = true
        holder.binding.shortProgress.progress = 0

        playJob?.cancel()
        playJob = viewLifecycleOwner.lifecycleScope.launch {
            val streams = runCatching { streamsFor(item.videoId).await() }.getOrElse {
                Log.e(TAG, "streams ${item.videoId}", it)
                if (_binding != null && currentPosition == position) {
                    holder.binding.shortBuffering.isVisible = false
                    Toast.makeText(requireContext(), R.string.unknown_error, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (_binding == null || currentPosition != position) return@launch
            adapter.setDetails(item.videoId, streams)
            if (holder.videoId == item.videoId) bindPage(holder, item, streams)

            val source = ShortsMediaSources.create(requireContext(), item.videoId, streams)
            if (source == null) {
                holder.binding.shortBuffering.isVisible = false
                Toast.makeText(requireContext(), R.string.unknown_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = isResumed
            startProgressUpdates()

            // Los dos siguientes se cargan mientras este se ve.
            list.drop(position + 1).take(2).forEach { streamsFor(it.videoId) }
        }
    }

    /** Los datos de un Short (una sola consulta por video, compartida con la precarga). */
    private fun streamsFor(videoId: String): Deferred<Streams> {
        streamsRequests[videoId]?.let { if (!it.isCancelled) return it }
        val request = viewLifecycleOwner.lifecycleScope.async(Dispatchers.IO) {
            MediaServiceRepository.instance.getStreams(videoId)
        }
        streamsRequests[videoId] = request
        // Solo se guardan los últimos 12 (los datos de un video no sirven mucho tiempo).
        if (streamsRequests.size > 12) {
            streamsRequests.keys.firstOrNull { it != videoId }?.let { streamsRequests.remove(it) }
        }
        return request
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val p = player
                val holder = currentHolder()
                if (p != null && holder != null && p.duration > 0) {
                    holder.binding.shortProgress.progress = (p.currentPosition * 1000 / p.duration).toInt()
                }
                delay(200)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindPage(holder: ShortsPagerAdapter.PageViewHolder, item: ShortItem, details: Streams?) {
        val b = holder.binding
        val context = b.root.context
        b.shortTitle.text = details?.title ?: item.title.orEmpty()
        val channelName = details?.uploader ?: item.channel
        b.shortChannelName.text = channelName?.let { if (it.startsWith("@")) it else "@" + it.replace(" ", "") }.orEmpty()
        b.shortLikeCount.text = details?.likes?.takeIf { it > 0 }?.formatShort() ?: context.getString(R.string.shorts_like)
        b.shortChannelRow.isVisible = channelName != null
        b.shortChannelSquare.isVisible = details?.uploaderAvatar != null
        details?.uploaderAvatar?.let {
            ImageHelper.loadImage(it, b.shortChannelAvatar)
            ImageHelper.loadImage(it, b.shortChannelSquareImage)
        }
        b.shortProgress.progress = 0
        applyPictureInPictureLayout(holder)
        if (holder.bindingAdapterPosition != currentPosition) {
            b.shortThumbnail.isVisible = true
            b.shortBuffering.isVisible = false
            b.shortPlayIcon.isVisible = false
        }

        // Tocar el video: pausa o sigue.
        b.root.setOnClickListener {
            if (commentsOpen) {
                closeComments()
                return@setOnClickListener
            }
            val p = player ?: return@setOnClickListener
            if (holder.bindingAdapterPosition != currentPosition) return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                b.shortPlayIcon.isVisible = true
            } else {
                p.play()
                b.shortPlayIcon.isVisible = false
            }
        }

        // Me gusta / No me gusta (marca en este equipo; YouTube no se toca sin cuenta).
        val accent = ContextCompat.getColor(context, R.color.shorts_red)
        fun paintVotes() {
            (b.shortLike.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(if (holder.liked) accent else Color.WHITE)
            (b.shortDislike.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(if (holder.disliked) accent else Color.WHITE)
        }
        paintVotes()
        b.shortLike.setOnClickListener {
            holder.liked = !holder.liked
            if (holder.liked) holder.disliked = false
            paintVotes()
        }
        b.shortDislike.setOnClickListener {
            holder.disliked = !holder.disliked
            if (holder.disliked) holder.liked = false
            paintVotes()
        }

        // Comentarios: abre el Short en el reproductor, donde están los comentarios y la descripción.
        // Comentarios como la app de YouTube: el Short se achica arriba y los comentarios abajo.
        b.shortComments.setOnClickListener {
            if (holder.bindingAdapterPosition == currentPosition) openComments(item, details)
        }

        b.shortShare.setOnClickListener {
            val url = "https://youtube.com/shorts/${item.videoId}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, b.shortTitle.text.toString())
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        }

        val channelId = details?.uploaderUrl?.toID()
        val openChannel = View.OnClickListener {
            if (channelId != null) {
                player?.pause()
                NavigationHelper.navigateChannel(context, channelId)
            }
        }
        b.shortChannelAvatar.setOnClickListener(openChannel)
        b.shortChannelName.setOnClickListener(openChannel)
        b.shortChannelSquare.setOnClickListener(openChannel)

        // Suscribirse (con la lógica de suscripciones de la app).
        b.shortSubscribe.isVisible = channelId != null
        if (details != null && channelId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val subscribed = runCatching {
                    withContext(Dispatchers.IO) { SubscriptionHelper.isSubscribed(channelId) }
                }.getOrNull() == true
                if (holder.videoId == item.videoId) paintSubscribe(holder, subscribed)
            }
            b.shortSubscribe.setOnClickListener {
                val subscribe = b.shortSubscribe.tag != true
                paintSubscribe(holder, subscribe)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        if (subscribe) {
                            SubscriptionHelper.subscribe(channelId, details.uploader, details.uploaderAvatar, details.uploaderVerified)
                        } else {
                            SubscriptionHelper.unsubscribe(channelId)
                        }
                    }.onFailure { Log.e(TAG, "subscribe", it) }
                }
            }
        }
    }

    private fun paintSubscribe(holder: ShortsPagerAdapter.PageViewHolder, subscribed: Boolean) {
        val button = holder.binding.shortSubscribe
        button.tag = subscribed
        if (subscribed) {
            button.setText(R.string.shorts_subscribed)
            button.setBackgroundResource(R.drawable.shorts_subscribed_bg)
            button.setTextColor(Color.WHITE)
        } else {
            button.setText(R.string.subscribe)
            button.setBackgroundResource(R.drawable.shorts_subscribe_bg)
            button.setTextColor(Color.parseColor("#0F0F0F"))
        }
    }

    companion object {
        private const val TAG = "ShortsFragment"

        /** Parte de la pantalla que ocupan los comentarios (el resto, el video). */
        private const val COMMENTS_HEIGHT_FRACTION = 0.62f
        private const val COMMENTS_ANIMATION_MS = 250L
    }
}
