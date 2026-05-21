package com.github.libretube.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Segment
import com.github.libretube.helpers.PlayerHelper

@UnstableApi
class PlayerViewModel : ViewModel() {

    var segments = MutableLiveData<List<Segment>>()
    var currentCaptionId: String? = null
    var sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()

    var isOrientationChangeInProgress = false
}