package com.github.libretube.ui.sheets

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.extensions.dpToPx
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.ui.adapters.BottomSheetAdapter
import kotlinx.coroutines.launch
import com.github.libretube.ui.extensions.onSystemInsets

open class BaseBottomSheet(@LayoutRes layoutResId: Int = R.layout.bottom_sheet) : ExpandedBottomSheet(layoutResId) {

    private var title: String? = null
    private lateinit var items: List<BottomSheetItem>
    private lateinit var listener: (index: Int) -> Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // MAGIA: Hacemos transparente el contenedor nativo para que nuestra píldora "flote"
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        val bg = GradientDrawable()
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        bg.setColor(typedValue.data)
        bg.cornerRadius = 40f
        view.background = bg
        view.clipToOutline = true

        val titleLayout = view.findViewById<View>(R.id.bottom_sheet_title_layout)
        val titleView = view.findViewById<TextView>(R.id.bottom_sheet_title)
        val recycler = view.findViewById<RecyclerView>(R.id.options_recycler)

        if (title != null && titleLayout != null && titleView != null) {
            titleLayout.isVisible = true

            titleView.text = title
            titleView.textSize = titleTextSize
            titleView.updateLayoutParams<MarginLayoutParams> {
                marginStart = titleMargin
                marginEnd = titleMargin
            }
        }

        if (recycler != null && ::items.isInitialized) {
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = BottomSheetAdapter(items, listener)

            recycler.onSystemInsets { v, systemInsets ->
                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    systemInsets.bottom
                )
            }
        }
    }

    fun setItems(items: List<BottomSheetItem>, listener: (suspend (index: Int) -> Unit)?) = apply {
        this.items = items
        this.listener = { index ->
            lifecycleScope.launch {
                dialog?.hide()
                listener?.invoke(index)
                runCatching {
                    dismiss()
                }
            }
        }
    }

    fun setTitle(title: String?) {
        this.title = title
    }

    fun setSimpleItems(
        titles: List<String>,
        preselectedItem: String? = null,
        listener: (suspend (index: Int) -> Unit)?
    ) = apply {
        setItems(titles.map { BottomSheetItem(it, isSelected = it == preselectedItem) }, listener)
    }

    companion object {
        private val titleTextSize = 7f.dpToPx().toFloat()
        private val titleMargin = 24f.dpToPx()
    }
}