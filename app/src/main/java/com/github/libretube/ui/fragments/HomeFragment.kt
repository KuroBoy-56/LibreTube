package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.databinding.FragmentHomeBinding
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.google.android.material.tabs.TabLayout

class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by activityViewModels()

    private val mainAdapter = VideoCardsAdapter()

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
        binding.mainFeedRv.adapter = mainAdapter

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

    private fun submitData(videos: List<com.github.libretube.api.obj.StreamItem>?) {
        val list = videos ?: emptyList()
        mainAdapter.submitList(list) {
            hideLoading(list.isNotEmpty())
        }
    }

    private fun loadContentForTab(position: Int) {
        if (position < 0 || position >= tabList.size) return

        showLoading()
        if (_binding != null) binding.nothingHere.isVisible = false
        mainAdapter.submitList(emptyList())

        val tabInfo = tabList[position]

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
}