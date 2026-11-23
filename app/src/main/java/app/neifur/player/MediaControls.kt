package app.neifur.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * The player as Android sees it.
 *
 * - **Audio focus:** starting an episode pauses music in other apps; a call or a voice message pauses the
 *   episode and, if it was the short kind, resumes it afterwards.
 * - **Media session:** headphone buttons work (one press plays or pauses, two go to the next episode), and so do
 *   the lock screen and watch controls.
 */
class MediaControls(
    ctx: Context,
    private val player: MpvPlayer,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val step: () -> Int,
) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var hasFocus = false
    /** Paused for a short interruption (a call, a navigator prompt): play again once it's over. */
    private var resumeOnGain = false

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        )
        // A notification sound only ducks us: Android lowers the volume by itself.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; resumeOnGain = false; player.pause(true) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { hasFocus = false; if (!player.paused) { resumeOnGain = true; player.pause(true) } }
                AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; if (resumeOnGain) { resumeOnGain = false; player.pause(false) } }
            }
        }, main)
        .build()

    private val session = MediaSession(ctx, "Neifur365").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = player.pause(false)
            override fun onPause() = player.pause(true)
            override fun onStop() = player.pause(true)
            override fun onSkipToNext() = onNext()
            override fun onSkipToPrevious() = onPrevious()
            override fun onFastForward() = player.seekBy(step().toDouble())
            override fun onRewind() = player.seekBy(-step().toDouble())
            override fun onSeekTo(pos: Long) = player.seekTo(pos / 1000.0)
        }, main)
        isActive = true
    }

    /** Called as playback starts or resumes. Refused or delayed (e.g. a call is on): stay paused. */
    fun takeFocus() {
        if (hasFocus) return
        resumeOnGain = false
        val r = audio.requestAudioFocus(focus)
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        when (r) {
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { resumeOnGain = true; player.pause(true) }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> player.pause(true)
        }
    }

    /** Re-arms headphone and system media buttons when the player comes back to the foreground. */
    fun activate() { session.isActive = true }

    /** Steps aside while the player is backgrounded so headphone presses don't wake a hidden episode. */
    fun deactivate() {
        session.isActive = false
        abandonFocus()
    }

    private fun abandonFocus() {
        hasFocus = false
        resumeOnGain = false
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    fun update(title: String, subtitle: String, playing: Boolean, position: Double, duration: Double, hasNext: Boolean, hasPrevious: Boolean) {
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    (position * 1000).toLong().coerceAtLeast(0L), if (playing) player.speed.toFloat() else 0f,
                )
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (duration * 1000).toLong().coerceAtLeast(0))
                .build()
        )
    }

    fun release() {
        abandonFocus()
        session.isActive = false
        session.release()
    }
}
