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
 * Una fila de Shorts del inicio (un elemento del RecyclerView del inicio): el logo, "Shorts" y
 * cuatro Shorts verticales en dos filas de dos, como la app de YouTube. Sin Shorts no ocupa nada.
 * El inicio pone una cada dos videos.
 */
class ShortsShelfAdapter(
    private val onShortClick: (items: List<ShortItem>, index: Int) -> Unit,
    private val onMoreClick: () -> Unit
) : RecyclerView.Adapter<ShortsShelfAdapter.ShelfViewHolder>() {

    private var items: List<ShortItem> = emptyList()

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

    override fun getItemCount() = if (items.isNotEmpty()) 1 else 0

    // Tipo propio: el inicio comparte el reciclado de vistas entre sus partes (ConcatAdapter sin
    // tipos aislados) y no debe chocar con los tipos de VideoCardsAdapter.
    override fun getItemViewType(position: Int) = VIEW_TYPE

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

    companion object {
        const val VIEW_TYPE = 7301
    }

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
