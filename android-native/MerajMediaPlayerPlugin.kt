package com.meraj.mediaplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Native playback bridge using Media3/ExoPlayer.
 * ExoPlayer is created and accessed exclusively on Android's main thread.
 */
@CapacitorPlugin(name = "MerajMediaPlayer")
class MerajMediaPlayerPlugin : Plugin() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var player: ExoPlayer? = null

    override fun load() {
        super.load()
        runOnMain {
            if (player == null) {
                player = ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            notifyListeners("playbackError", JSObject().apply {
                                put("message", error.message ?: "This file's codec/container is not supported on this device.")
                                put("errorCode", error.errorCode)
                            })
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val current = player
                            notifyListeners("playbackState", JSObject().apply {
                                put("state", playbackState)
                                put("isPlaying", current?.isPlaying == true)
                                put("position", current?.currentPosition ?: 0L)
                                put("duration", (current?.duration ?: 0L).coerceAtLeast(0L))
                            })
                        }
                    })
                }
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    @PluginMethod
    fun play(call: PluginCall) {
        val uriText = call.getString("uri")
        if (uriText.isNullOrBlank()) {
            call.reject("A local content URI is required.")
            return
        }
        runOnMain {
            try {
                val p = player ?: throw IllegalStateException("Media player is not initialized.")
                p.setMediaItem(MediaItem.fromUri(Uri.parse(uriText)))
                p.prepare()
                p.playWhenReady = true
                call.resolve(JSObject().put("accepted", true))
            } catch (e: Exception) {
                call.reject("Unable to open media: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        runOnMain {
            val p = player
            call.resolve(JSObject().apply {
                put("isPlaying", p?.isPlaying == true)
                put("position", p?.currentPosition ?: 0L)
                put("duration", (p?.duration ?: 0L).coerceAtLeast(0L))
                put("playbackState", p?.playbackState ?: Player.STATE_IDLE)
            })
        }
    }

    @PluginMethod
    fun setRepeatOne(call: PluginCall) {
        val enabled = call.getBoolean("enabled", false) ?: false
        runOnMain {
            val p = player
            if (p == null) {
                call.reject("Media player is not initialized.")
                return@runOnMain
            }
            p.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            call.resolve(JSObject().put("enabled", enabled))
        }
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        runOnMain {
            player?.pause()
            call.resolve(JSObject().put("paused", true))
        }
    }

    @PluginMethod
    fun resume(call: PluginCall) {
        runOnMain {
            player?.play()
            call.resolve(JSObject().put("playing", true))
        }
    }

    @PluginMethod
    fun seekTo(call: PluginCall) {
        val position = call.getLong("positionMs")
        if (position == null || position < 0) {
            call.reject("positionMs must be a non-negative number.")
            return
        }
        runOnMain {
            val p = player
            if (p == null) {
                call.reject("Media player is not initialized.")
                return@runOnMain
            }
            p.seekTo(position)
            call.resolve(JSObject().put("positionMs", position))
        }
    }

    override fun handleOnDestroy() {
        runOnMain {
            player?.release()
            player = null
        }
        super.handleOnDestroy()
    }
}
