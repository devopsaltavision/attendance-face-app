package com.syntaxgenie.hfx05attendance.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/** Short, best-effort UI feedback sounds shared by every attendance method. */
class AppSoundManager(context: Context) {
    enum class Event { SCAN_READY, CAPTURE_ACCEPTED, SUCCESS, WARNING, ERROR }

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var toneGenerator: ToneGenerator? = null

    fun play(event: Event) {
        if (!isPlaybackEnabled(preferences.getBoolean(SOUND_FEEDBACK_ENABLED, true))) return
        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { toneGenerator = it }
            tone.startTone(event.toneType, event.durationMs)
        }
    }

    fun release() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }

    fun isEnabled(): Boolean = preferences.getBoolean(SOUND_FEEDBACK_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(SOUND_FEEDBACK_ENABLED, enabled).apply()
    }

    private val Event.toneType: Int get() = when (this) {
        Event.SCAN_READY -> ToneGenerator.TONE_PROP_BEEP
        Event.CAPTURE_ACCEPTED -> ToneGenerator.TONE_PROP_ACK
        Event.SUCCESS -> ToneGenerator.TONE_PROP_ACK
        Event.WARNING -> ToneGenerator.TONE_PROP_BEEP2
        Event.ERROR -> ToneGenerator.TONE_PROP_NACK
    }

    private val Event.durationMs: Int get() = when (this) {
        Event.SCAN_READY -> 180
        Event.CAPTURE_ACCEPTED -> 200
        Event.SUCCESS -> 260
        Event.WARNING -> 240
        Event.ERROR -> 320
    }

    companion object {
        private const val PREFERENCES = "app_sound_feedback"
        private const val SOUND_FEEDBACK_ENABLED = "sound_feedback_enabled"
        // App-local ToneGenerator gain only. Never changes the device's global stream volume.
        private const val TONE_VOLUME = 90

        internal fun isPlaybackEnabled(soundFeedbackEnabled: Boolean): Boolean = soundFeedbackEnabled
    }
}
