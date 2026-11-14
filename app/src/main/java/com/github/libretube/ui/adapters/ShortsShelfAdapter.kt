package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.ShortItem
import com.github.libretube.databinding.ItemShortCardBinding
import com.github.libretube.databinding.ItemShortsShelfBinding
import com.github.libretube.helpers.ImageHelper

/**
 * La fila de Shorts del inicio (una sola fila del RecyclerView del inicio): el logo, "Shorts" y
 * cuatro Shorts verticales en dos filas de dos, como la app de YouTube. Sin Shorts no ocupa nada.
 */
class ShortsShelfAdapter(
    private val onShortClick: (items: List<ShortItem>, index: Int) -> Unit,
    private val onMoreClick: () -> Unit
) : RecyclerView.Adapter<ShortsShelfAdapter.ShelfViewHolder>() {

    private var items: List<ShortItem> = emptyList()
    private var visible = true

    fun submit(newItems: List<ShortItem>) {
        val wasShown = itemCount > 0
        items = newItems
        val isShown = itemCount > 0
        when {
            wasShown && isShown -> notifyItemChanged(0)
            wasShown -> notifyItemRemoved(0)
            isShown -> notifyItemInserted(0)
        }
    }

    /** Oculta la fila (por ejemplo, mientras el inicio carga o sin videos). */
    fun setVisible(show: Boolean) {
        if (show == visible) return
        val wasShown = itemCount > 0
        visible = show
        val isShown = itemCount > 0
        if (wasShown && !isShown) notifyItemRemoved(0)
        if (!wasShown && isShown) notifyItemInserted(0)
    }

    override fun getItemCount() = if (visible && items.isNotEmpty()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfViewHolder {
        val binding = ItemShortsShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.shortsShelfGrid.layoutManager = object : GridLayoutManager(parent.context, 2) {
            override fun canScrollVertically() = false
        }
        return ShelfViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShelfViewHolder, position: Int) {
        holder.binding.shortsShelfGrid.adapter = ShortCardsAdapter(items, onShortClick)
        holder.binding.shortsShelfMore.setOnClickListener { onMoreClick() }
    }

    class ShelfViewHolder(val binding: ItemShortsShelfBinding) : RecyclerView.ViewHolder(binding.root)

    private class ShortCardsAdapter(
        private val items: List<ShortItem>,
        private val onShortClick: (items: List<ShortItem>, index: Int) -> Unit
    ) : RecyclerView.Adapter<ShortCardsAdapter.CardViewHolder>() {

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardViewHolder(
            ItemShortCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            val item = items[position]
            with(holder.binding) {
                ImageHelper.loadImage(item.thumbnail, shortCardThumbnail)
                shortCardTitle.text = item.title.orEmpty()
                shortCard.setOnClickListener {
                    onShortClick(items, holder.bindingAdapterPosition)
                }
            }
        }

        class CardViewHolder(val binding: ItemShortCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
