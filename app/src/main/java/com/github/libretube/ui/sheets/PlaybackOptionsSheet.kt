package com.github.libretube.ui.sheets

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.PlaybackBottomSheetBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.round
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.ui.adapters.SliderLabelsAdapter
import kotlin.math.absoluteValue
import kotlin.math.log
import kotlin.math.pow

class PlaybackOptionsSheet(
    private val player: MediaController
) : BaseBottomSheet(R.layout.playback_bottom_sheet) { // Hereda del nuevo molde BaseBottomSheet para que flote
    private var _binding: PlaybackBottomSheetBinding? = null
    private val binding get() = _binding!!

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // Inicializa el redondeo del BaseBottomSheet
        _binding = PlaybackBottomSheetBinding.bind(view)

        binding.speedShortcuts.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        binding.speedShortcuts.adapter = SliderLabelsAdapter(SUGGESTED_SPEEDS) {
            binding.speed.value = it
        }

        binding.speed.value = player.playbackParameters.speed
        binding.pitch.value = playbackPitchToSemitone(player.playbackParameters.pitch)

        val currentSemitone = binding.pitch.value.round(2)
        binding.semitoneEditText.setText(currentSemitone.toString())
        binding.pitchResetButton.isGone = currentSemitone == 0f

        binding.semitoneEditText.setOnEditorActionListener { editText, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return@setOnEditorActionListener false
            }

            if (editText.text.isEmpty()) {
                binding.semitoneEditText.setText(binding.pitch.value.round(2).toString())
                clearEditTextFocusAndHideKeyboard()
                return@setOnEditorActionListener false
            }

            val enteredSemitoneValue = editText.text.toString().toFloat()
            if (enteredSemitoneValue.absoluteValue > SEMITONES_IN_ONE_OCTAVE) {
                editText.error = context?.getString(
                    R.string.playback_pitch_semitone_error_input,
                    SEMITONES_IN_ONE_OCTAVE
                )
                return@setOnEditorActionListener true
            }
            changePlaybackPitchInSemitone(enteredSemitoneValue)
            clearEditTextFocusAndHideKeyboard()

            true
        }

        binding.pitchResetButton.setOnClickListener {
            changePlaybackPitchInSemitone(0f)
            clearEditTextFocusAndHideKeyboard()
        }

        binding.semitoneDecrementButton.setOnClickListener {
            incrementDecrementSemitoneBy(-1.0f)
            clearEditTextFocusAndHideKeyboard()
        }

        binding.semitoneIncrementButton.setOnClickListener {
            incrementDecrementSemitoneBy(1.0f)
            clearEditTextFocusAndHideKeyboard()
        }

        PreferenceHelper.getBoolean(PreferenceKeys.SKIP_SILENCE, false).let {
            binding.skipSilence.isChecked = it
        }

        binding.speed.addOnChangeListener { _, _, _ ->
            onChange()
        }
        binding.speed.setLabelFormatter { value ->
            return@setLabelFormatter "${value}x"
        }

        binding.pitch.addOnChangeListener { _, _, _ ->
            onChange()
        }
        binding.pitch.setLabelFormatter { value ->
            return@setLabelFormatter "${value.round(2)}"
        }

        binding.skipSilence.setOnCheckedChangeListener { _, isChecked ->
            player.sendCustomCommand(
                AbstractPlayerService.runPlayerActionCommand,
                bundleOf(PlayerCommand.SKIP_SILENCE.name to isChecked)
            )
            PreferenceHelper.putBoolean(PreferenceKeys.SKIP_SILENCE, isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onChange() {
        player.playbackParameters = PlaybackParameters(
            binding.speed.value.round(2),
            semitoneToPlaybackPitch(binding.pitch.value)
        )
        binding.semitoneEditText.setText((binding.pitch.value).round(2).toString())
        binding.pitchResetButton.isGone = binding.pitch.value == 0f

        val currentSpeed = player.playbackParameters.speed.toString()
        PreferenceHelper.putString(PreferenceKeys.PLAYBACK_SPEED, currentSpeed)
    }

    private fun clearEditTextFocusAndHideKeyboard() {
        val inputMethodManager =
            context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(binding.semitoneEditText.windowToken, 0)
        binding.semitoneEditText.clearFocus()
        binding.semitoneEditText.error = null
    }

    private fun changePlaybackPitchInSemitone(semitone: Float) {
        binding.pitch.value = semitone
        onChange()
    }

    private fun semitoneToPlaybackPitch(semitone: Float): Float {
        return SEMITONE_RATIO.pow(semitone).toFloat()
    }

    private fun playbackPitchToSemitone(playbackPitch: Float): Float {
        return log(playbackPitch, SEMITONE_RATIO)
    }

    private fun incrementDecrementSemitoneBy(value: Float) {
        var currentSemitone = binding.pitch.value
        currentSemitone = currentSemitone + value
        currentSemitone =
            currentSemitone.coerceIn(-SEMITONES_IN_ONE_OCTAVE, SEMITONES_IN_ONE_OCTAVE)
        changePlaybackPitchInSemitone(currentSemitone)
    }

    companion object {
        private val SUGGESTED_SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        private const val SEMITONE_RATIO = 1.059463f
        private const val SEMITONES_IN_ONE_OCTAVE = 12.0f
    }
}