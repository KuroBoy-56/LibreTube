package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.ShortItem
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.FragmentHomeBinding
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.adapters.ShortsShelfAdapter
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.models.ShortsViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.google.android.material.tabs.TabLayout
import kotlin.random.Random

class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by activityViewModels()

    private val shortsViewModel: ShortsViewModel by activityViewModels()

    // Como la app de YouTube: cada dos videos una fila de Shorts (2 x 2), hasta 20 filas; en cada
    // carga el inicio empieza al azar por una fila de Shorts o por dos videos. Las vistas se
    // reciclan entre todas las partes (tipos de vista compartidos).
    private val feedAdapter = ConcatAdapter(
        ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build()
    )
    private val videoSegments = mutableListOf<VideoCardsAdapter>()
    private val shelfSegments = mutableListOf<ShortsShelfAdapter>()
    private val tailAdapter = VideoCardsAdapter()
    private var shelvesFirst = false

    /** Shorts de la pestaña cargada (los de la búsqueda de su tema o el feed de Shorts). */
    private var homeShorts: List<ShortItem> = emptyList()
    private var homeShortsKey = -1
    private var shelvesAllowed = false

    private enum class TabType { SUGERENCIAS, TODOS, TRENDING, CUSTOM, SUSCRIPCIONES }
    private data class TabInfo(
        val title: String,
        val type: TabType,
        val trendingCategory: TrendingCategory? = null,
        val customQuery: String? = null
    )
    private val tabList = mutableListOf<TabInfo>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.changeInstance.visibility = View.GONE

        binding.mainFeedRv.layoutManager = LinearLayoutManager(requireContext())
        videoSegments.clear()
        shelfSegments.clear()
        feedAdapter.adapters.toList().forEach { feedAdapter.removeAdapter(it) }
        feedAdapter.addAdapter(tailAdapter)
        binding.mainFeedRv.adapter = feedAdapter

        shortsViewModel.homeShorts.observe(viewLifecycleOwner) { result ->
            if (result.key != homeShortsKey) return@observe
            homeShorts = result.items
            updateShelves()
        }

        val categories = MediaServiceRepository.instance.getTrendingCategories()
            .filter { !it.name.contains("PODCAST", ignoreCase = true) }
        val customCategories = listOf("Música Asiática", "Acción y Películas", "Estilo de Vida")

        tabList.clear()

        tabList.add(TabInfo("Sugerencias para ti", TabType.SUGERENCIAS, customQuery = "Sugerencias para ti"))
        tabList.add(TabInfo("Todos", TabType.TODOS))

        categories.forEach { category ->
            val tabTitle = if (category.name.contains("DEFAULT") || category.name.contains("ALL")) {
                "Tendencias"
            } else {
                getString(category.titleRes)
            }
            tabList.add(TabInfo(tabTitle, TabType.TRENDING, trendingCategory = category))
        }

        customCategories.forEach { customName ->
            tabList.add(TabInfo(customName, TabType.CUSTOM, customQuery = customName))
        }

        tabList.add(TabInfo("Suscripciones", TabType.SUSCRIPCIONES))

        tabList.forEach { info ->
            binding.categoryTabs.addTab(binding.categoryTabs.newTab().setText(info.title))
        }

        binding.categoryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                loadContentForTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                binding.mainFeedRv.smoothScrollToPosition(0)
            }
        })

        homeViewModel.todosFeed.observe(viewLifecycleOwner) { videos ->
            val pos = binding.categoryTabs.selectedTabPosition
            if (pos >= 0 && pos < tabList.size && tabList[pos].type == TabType.TODOS) {
                submitData(videos)
            }
        }

        homeViewModel.categoryFeed.observe(viewLifecycleOwner) { videos ->
            val pos = binding.categoryTabs.selectedTabPosition
            if (pos >= 0 && pos < tabList.size && tabList[pos].type == TabType.TRENDING) {
                submitData(videos)
            }
        }

        homeViewModel.customFeed.observe(viewLifecycleOwner) { videos ->
            val pos = binding.categoryTabs.selectedTabPosition
            if (pos >= 0 && pos < tabList.size) {
                val type = tabList[pos].type
                if (type == TabType.CUSTOM || type == TabType.SUGERENCIAS) {
                    submitData(videos)
                }
            }
        }

        homeViewModel.subsFeed.observe(viewLifecycleOwner) { videos ->
            val pos = binding.categoryTabs.selectedTabPosition
            if (pos >= 0 && pos < tabList.size && tabList[pos].type == TabType.SUSCRIPCIONES) {
                submitData(videos)
            }
        }

        homeViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                showLoading()
            } else {
                if (_binding == null) return@observe // Seguro extra
                binding.progress.isVisible = false
                binding.refresh.isRefreshing = false
                binding.mainFeedRv.alpha = 1.0f
            }
        }

        binding.refresh.setOnRefreshListener {
            binding.refresh.isRefreshing = true
            loadContentForTab(binding.categoryTabs.selectedTabPosition)
        }

        binding.refreshButton.setOnClickListener {
            loadContentForTab(binding.categoryTabs.selectedTabPosition)
        }

        loadContentForTab(0)
    }

    private fun submitData(videos: List<StreamItem>?) {
        val list = videos ?: emptyList()
        renderFeed(list) {
            hideLoading(list.isNotEmpty())
        }
    }

    /**
     * Arma el inicio: grupos de dos videos con una fila de Shorts entre cada grupo (antes del
     * grupo si esta carga empieza por Shorts), hasta [MAX_SHELVES] filas; el resto de los videos
     * va al final. Las partes que ya existen solo se actualizan (no se pierde la posición).
     */
    private fun renderFeed(list: List<StreamItem>, onCommitted: (() -> Unit)? = null) {
        val chunks = list.take(MAX_SHELVES * VIDEOS_PER_GROUP).chunked(VIDEOS_PER_GROUP)
        val rest = list.drop(MAX_SHELVES * VIDEOS_PER_GROUP)

        while (videoSegments.size > chunks.size) {
            feedAdapter.removeAdapter(videoSegments.removeAt(videoSegments.lastIndex))
            feedAdapter.removeAdapter(shelfSegments.removeAt(shelfSegments.lastIndex))
        }
        while (videoSegments.size < chunks.size) {
            val group = videoSegments.size
            val videos = VideoCardsAdapter()
            val shelf = ShortsShelfAdapter(
                onShortClick = { items, index -> openShorts(group, items, index) },
                onMoreClick = { openShorts(group, emptyList(), 0) }
            )
            // Siempre antes del adaptador final (el resto de los videos).
            val insertAt = feedAdapter.adapters.size - 1
            if (shelvesFirst) {
                feedAdapter.addAdapter(insertAt, shelf)
                feedAdapter.addAdapter(insertAt + 1, videos)
            } else {
                feedAdapter.addAdapter(insertAt, videos)
                feedAdapter.addAdapter(insertAt + 1, shelf)
            }
            videoSegments.add(videos)
            shelfSegments.add(shelf)
        }

        chunks.forEachIndexed { i, chunk -> videoSegments[i].submitList(chunk) }
        tailAdapter.submitList(rest) { onCommitted?.invoke() }
        updateShelves()
    }

    /** Pone en cada fila sus cuatro Shorts (fila 1: Shorts 1 a 4, fila 2: 5 a 8, ...). */
    private fun updateShelves() {
        shelfSegments.forEachIndexed { i, shelf ->
            val slice = if (shelvesAllowed) {
                homeShorts.drop(i * SHORTS_PER_SHELF).take(SHORTS_PER_SHELF)
            } else {
                emptyList()
            }
            // Solo filas completas (2 x 2), como la app de YouTube.
            shelf.submit(if (slice.size == SHORTS_PER_SHELF) slice else emptyList())
        }
    }

    /**
     * Abre la sección Shorts en el Short tocado: siguen los de esa fila y los de las filas de
     * abajo (del mismo tema) y después el feed de Shorts.
     */
    private fun openShorts(group: Int, shelfItems: List<ShortItem>, index: Int) {
        val fromShelf = homeShorts.drop(group * SHORTS_PER_SHELF).take(MAX_OPEN_SHORTS)
        val items = fromShelf.ifEmpty { shelfItems }
        if (items.isNotEmpty()) shortsViewModel.startFrom(items, index.coerceIn(0, items.lastIndex))
        (activity as? MainActivity)?.openShortsTab()
    }

    /** Temas de los Shorts de cada pestaña (null = el feed de la pestaña Shorts de YouTube). */
    private fun shortsTopicsFor(tab: TabInfo): List<String>? = when (tab.type) {
        TabType.SUGERENCIAS, TabType.TODOS, TabType.SUSCRIPCIONES -> null
        TabType.TRENDING -> when (tab.trendingCategory) {
            TrendingCategory.MUSIC -> listOf("música", "canciones", "music", "música nueva")
            TrendingCategory.GAMING -> listOf("videojuegos", "gaming", "gameplay", "juegos")
            TrendingCategory.TRAILERS -> listOf("tráiler película", "películas", "escenas de películas", "series")
            TrendingCategory.LIVE -> listOf("en vivo", "transmisión en vivo", "live")
            else -> null // Tendencias: el feed de Shorts
        }
        TabType.CUSTOM -> when (tab.customQuery) {
            "Música Asiática" -> listOf("kpop", "jpop", "música asiática", "asian pop music", "anime opening", "cpop")
            "Acción y Películas" -> listOf("escenas de acción", "películas de acción", "action movie scenes", "tráiler acción")
            "Estilo de Vida" -> listOf("estilo de vida", "rutina diaria", "day in my life", "lifestyle vlog")
            else -> tab.customQuery?.let { listOf(it) }
        }
    }

    private fun loadContentForTab(position: Int) {
        if (position < 0 || position >= tabList.size) return

        showLoading()
        if (_binding != null) binding.nothingHere.isVisible = false

        val tabInfo = tabList[position]

        // Cada carga: el inicio vacío, de nuevo al azar si empieza por Shorts o por dos videos, y
        // Shorts nuevos del tema de la pestaña (en Suscripciones no hay filas de Shorts).
        homeShorts = emptyList()
        renderFeed(emptyList())
        shelvesFirst = Random.nextBoolean()
        shelvesAllowed = tabInfo.type != TabType.SUSCRIPCIONES
        if (shelvesAllowed) {
            homeShortsKey = shortsViewModel.loadHomeShorts(
                requireContext(),
                shortsTopicsFor(tabInfo),
                personal = tabInfo.type == TabType.SUGERENCIAS,
                wanted = MAX_SHELVES * SHORTS_PER_SHELF
            )
        } else {
            homeShortsKey = -1
        }

        when (tabInfo.type) {
            TabType.SUGERENCIAS, TabType.CUSTOM -> {
                homeViewModel.loadCustomCategoryFallback(requireContext(), tabInfo.customQuery!!)
            }
            TabType.TODOS -> {
                homeViewModel.loadTodos(requireContext(), subscriptionsViewModel)
            }
            TabType.TRENDING -> {
                homeViewModel.loadOfficialCategory(requireContext(), tabInfo.trendingCategory!!)
            }
            TabType.SUSCRIPCIONES -> {
                homeViewModel.loadSubscriptionsOnly(subscriptionsViewModel)
            }
        }
    }

    private fun showLoading() {
        if (_binding == null) return // SALVAVIDAS
        binding.progress.isVisible = !binding.refresh.isRefreshing
        binding.nothingHere.isVisible = false
        binding.mainFeedRv.alpha = 0.3f
    }

    private fun hideLoading(hasContent: Boolean) {
        if (_binding == null) return // SALVAVIDAS: Evita el crasheo si te sales antes de que cargue
        binding.progress.isVisible = false
        binding.refresh.isRefreshing = false
        binding.mainFeedRv.alpha = 1.0f

        binding.nothingHere.isVisible = !hasContent
        binding.mainFeedRv.isVisible = hasContent
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Videos entre una fila de Shorts y la siguiente, como en la app de YouTube. */
        private const val VIDEOS_PER_GROUP = 2

        /** Filas de Shorts como máximo en el inicio. */
        private const val MAX_SHELVES = 20

        private const val SHORTS_PER_SHELF = 4

        /** Shorts que se pasan a la sección Shorts al tocar uno del inicio. */
        private const val MAX_OPEN_SHORTS = 60
    }
}
