package com.meraj.mediaplayer

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

/** Full-screen video UI attached to the single ExoPlayer owned by MerajPlaybackService. */
class NativeVideoPlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var listener: Player.Listener? = null
    private var previousQueue: List<MediaItem> = emptyList()
    private var previousIndex = 0
    private var previousPosition = 0L
    private var previousPlayWhenReady = false
    private var queueSnapshotTaken = false
    private var videoUri: Uri? = null
    private var videoTitle = "Video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
        }
        setContentView(playerView)
        val rawUri = intent.getStringExtra(EXTRA_URI)
        if (rawUri.isNullOrBlank()) { finish(); return }
        videoUri = Uri.parse(rawUri)
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: videoUri?.lastPathSegment ?: "Video"
        val token = SessionToken(this, ComponentName(this, MerajPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val c = future.get()
                controller = c
                playerView.player = c
                snapshotQueue(c)
                val uri = videoUri ?: return@addListener
                val item = MediaItem.Builder().setMediaId(uri.toString()).setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build()).build()
                c.setMediaItem(item)
                c.prepare()
                c.play()
                val l = object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("NiceMultimediaVideo", "Shared Media3 playback failed: ${error.errorCodeName}", error)
                    }
                }
                listener = l
                c.addListener(l)
            } catch (e: Exception) {
                android.util.Log.e("NiceMultimediaVideo", "Could not connect to shared MediaSession", e)
                runOnUiThread { finish() }
            }
        }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    }

    private fun snapshotQueue(p: MediaController) {
        if (queueSnapshotTaken) return
        previousQueue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        previousIndex = p.currentMediaItemIndex.coerceAtLeast(0)
        previousPosition = p.currentPosition.coerceAtLeast(0L)
        previousPlayWhenReady = p.playWhenReady
        queueSnapshotTaken = true
    }

    override fun onStop() { super.onStop() /* Keep shared player alive across Activity lifecycle changes. */ }

    override fun onDestroy() {
        playerView.player = null
        val c = controller
        listener?.let { c?.removeListener(it) }
        if (isFinishing && queueSnapshotTaken && c != null) {
            try {
                if (previousQueue.isNotEmpty()) {
                    c.setMediaItems(previousQueue, previousIndex.coerceIn(0, previousQueue.lastIndex), previousPosition)
                    c.prepare()
                    if (previousPlayWhenReady) c.play() else c.pause()
                } else c.clearMediaItems()
            } catch (e: Exception) { android.util.Log.w("NiceMultimediaVideo", "Could not restore previous audio queue", e) }
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "com.meraj.mediaplayer.VIDEO_URI"
        const val EXTRA_TITLE = "com.meraj.mediaplayer.VIDEO_TITLE"
    }
}
