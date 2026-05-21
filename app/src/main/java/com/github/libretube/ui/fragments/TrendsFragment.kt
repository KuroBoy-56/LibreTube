package com.github.libretube.ui.fragments

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentTrendsBinding
import com.github.libretube.databinding.FragmentTrendsContentBinding
import com.github.libretube.extensions.serializable
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.models.TrendsViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class TrendsFragment : Fragment(R.layout.fragment_trends) {
    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTrendsBinding.bind(view)

        val allCategories = MediaServiceRepository.instance.getTrendingCategories()
        val currentPref = PreferenceHelper.getString(
            PreferenceKeys.TRENDING_CATEGORY,
            TrendingCategory.MUSIC.name
        )
        val activeCategory = allCategories.find { it.name == currentPref } ?: allCategories.first()
        val categories = listOf(activeCategory)

        val adapter = TrendsAdapter(this, categories)
        binding.pager.adapter = adapter
        binding.pager.isUserInputEnabled = false

        binding.tabLayout.isGone = true

        binding.trendingRegion.setOnClickListener {
            showChangeRegionDialog(requireContext()) {
                adapter.getFragmentAt(binding.pager.currentItem)?.also {
                    it.refreshTrending()
                }
            }
        }
    }

    companion object {
        fun showChangeRegionDialog(context: Context, onPositiveButtonClick: () -> Unit) {
            val currentRegionPref = PreferenceHelper.getTrendingRegion(context)

            val countries = LocaleHelper.getAvailableCountries()
            var selected = countries.indexOfFirst { it.code == currentRegionPref }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.region)
                .setSingleChoiceItems(
                    countries.map { it.name }.toTypedArray(),
                    selected
                ) { _, checked ->
                    selected = checked
                }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.okay) { _, _ ->
                    PreferenceHelper.putString(PreferenceKeys.REGION, countries[selected].code)
                    onPositiveButtonClick()
                }
                .show()
        }
    }
}

class TrendsAdapter(fragment: Fragment, private val categories: List<TrendingCategory>) :
    FragmentStateAdapter(fragment) {
    private val fragments: MutableList<TrendsContentFragment?> =
        MutableList(categories.size) { null }

    override fun createFragment(position: Int): Fragment {
        val trendContentFragment = TrendsContentFragment().apply {
            arguments = bundleOf(
                IntentData.category to categories[position]
            )
        }
        fragments[position] = trendContentFragment
        return trendContentFragment
    }

    override fun getItemCount(): Int {
        return categories.size
    }

    fun getFragmentAt(position: Int): TrendsContentFragment? {
        return fragments[position]
    }
}

class TrendsContentFragment : DynamicLayoutManagerFragment(R.layout.fragment_trends_content) {
    private var _binding: FragmentTrendsContentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrendsViewModel by activityViewModels()

    private var _category: TrendingCategory? = null
    private val category get() = _category!!

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.recview?.layoutManager = LinearLayoutManager(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTrendsContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        _category = requireArguments()
            .serializable<TrendingCategory>(IntentData.category)!!

        val adapter = VideoCardsAdapter()
        binding.recview.adapter = adapter
        binding.recview.layoutManager?.onRestoreInstanceState(viewModel.recyclerViewState)

        viewModel.trendingVideos.observe(viewLifecycleOwner) { categoryMap ->
            val videos = categoryMap[category]
            if (videos == null) return@observe

            toggleLoadingIndicator(false)

            adapter.submitList(videos.streams)

            val trendingRegion = PreferenceHelper.getTrendingRegion(requireContext())
            if (videos.streams.isEmpty() && (videos.region == trendingRegion)) {
                Snackbar.make(
                    requireParentFragment().requireView(),
                    R.string.change_region,
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.change) {
                        TrendsFragment.showChangeRegionDialog(requireContext()) {
                            refreshTrending()
                        }
                    }
                    .show()
            }
        }

        binding.homeRefresh.isEnabled = true
        binding.homeRefresh.setOnRefreshListener {
            refreshTrending()
        }

        binding.recview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                viewModel.recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        viewModel.fetchTrending(requireContext(), category)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val trendingRegion = PreferenceHelper.getTrendingRegion(requireContext())
                val trendingVideos = viewModel.trendingVideos.value.orEmpty()[category]
                if (trendingVideos == null || (trendingVideos.region != trendingRegion)) {
                    refreshTrending()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.recview.layoutManager?.onRestoreInstanceState(viewModel.recyclerViewState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toggleLoadingIndicator(show: Boolean) {
        binding.recview.alpha = if (show) 0.3f else 1.0f
        binding.progressBar.isGone = !show
        if (!show) binding.homeRefresh.isRefreshing = false
    }

    fun refreshTrending() {
        toggleLoadingIndicator(true)
        viewModel.fetchTrending(requireContext(), category)
    }
}