package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.ShortItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.ItemShortPageBinding
import com.github.libretube.helpers.ImageHelper

/**
 * Las páginas de la sección Shorts (una por Short, a pantalla completa). El video lo pone
 * [com.github.libretube.ui.fragments.ShortsFragment] en la página que se está viendo; aquí solo
 * la miniatura y los datos del video (título, canal, me gusta) cuando llegan.
 */
class ShortsPagerAdapter(
    private val onBindPage: (holder: PageViewHolder, item: ShortItem, details: Streams?) -> Unit
) : ListAdapter<ShortItem, ShortsPagerAdapter.PageViewHolder>(DIFF) {

    /** Datos de cada Short ya cargado (videoId → Streams). */
    private val details = HashMap<String, Streams>()

    fun setDetails(videoId: String, streams: Streams) {
        details[videoId] = streams
    }

    fun getDetails(videoId: String): Streams? = details[videoId]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(
        ItemShortPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val item = getItem(position)
        holder.videoId = item.videoId
        ImageHelper.loadImage(item.thumbnail, holder.binding.shortThumbnail)
        onBindPage(holder, item, details[item.videoId])
    }

    class PageViewHolder(val binding: ItemShortPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var videoId: String? = null
        var liked = false
        var disliked = false
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ShortItem>() {
            override fun areItemsTheSame(oldItem: ShortItem, newItem: ShortItem) =
                oldItem.videoId == newItem.videoId

            override fun areContentsTheSame(oldItem: ShortItem, newItem: ShortItem) =
                oldItem == newItem
        }
    }
}
