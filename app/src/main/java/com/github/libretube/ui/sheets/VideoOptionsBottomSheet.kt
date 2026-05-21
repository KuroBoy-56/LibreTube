package com.github.libretube.ui.sheets

import android.os.Bundle
import androidx.core.os.bundleOf
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.toID
import com.github.libretube.ui.dialogs.AddToPlaylistDialog

/**
 * Dialog ultra minimalista con solo la opción de Añadir a Playlist.
 */
class VideoOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var streamItem: StreamItem

    override fun onCreate(savedInstanceState: Bundle?) {
        streamItem = arguments?.parcelable(IntentData.streamItem)!!

        val videoId = streamItem.url?.toID() ?: return

        setTitle(streamItem.title)

        // MINIMALISMO EXTREMO: Única opción disponible
        val optionsList = listOf(R.string.addToPlaylist)

        setSimpleItems(optionsList.map { getString(it) }) { which ->
            when (optionsList[which]) {
                // Función para añadir el audio a una Playlist
                R.string.addToPlaylist -> {
                    AddToPlaylistDialog().apply {
                        arguments = bundleOf(IntentData.videoInfo to streamItem)
                    }.show(
                        parentFragmentManager,
                        AddToPlaylistDialog::class.java.name
                    )
                }
            }
        }

        super.onCreate(savedInstanceState)
    }

    companion object {
        const val VIDEO_OPTIONS_SHEET_REQUEST_KEY = "video_options_sheet_request_key"
    }
}