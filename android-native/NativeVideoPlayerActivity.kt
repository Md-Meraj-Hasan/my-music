package com.meraj.mediaplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

/** Full-screen controls attached to the single ExoPlayer owned by MerajPlaybackService. */
class NativeVideoPlayerActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var playButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var positionLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var speedButton: TextView
    private lateinit var fitButton: TextView
    private lateinit var rotateButton: TextView
    private lateinit var lockButton: TextView
    private lateinit var shareButton: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pinchScale = 1f
    private var isPinching = false
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
    private var locked = false
    private var fitMode = 0
    private var speed = 1f
    private var volumeMuted = false
    private var seeking = false
    private var lastTap = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { if (!locked) controls.visibility = View.GONE }
    private val progress = object : Runnable {
        override fun run() {
            val p = controller
            if (p != null && !seeking) {
                val duration = p.duration.takeIf { it > 0L } ?: 0L
                if (!seeking) {
                    seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    seekBar.progress = p.currentPosition.coerceAtMost(seekBar.max.toLong()).toInt()
                    positionLabel.text = formatTime(p.currentPosition)
                }
                durationLabel.text = formatTime(duration)
                playButton.setImageResource(android.R.drawable.ic_media_pause.takeIf { p.isPlaying } ?: android.R.drawable.ic_media_play)
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                handler.removeCallbacks(hideControls)
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale = (pinchScale * detector.scaleFactor).coerceIn(1f, 2f)
                playerView.scaleX = pinchScale
                playerView.scaleY = pinchScale
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinching = false
                if (pinchScale <= 1.02f) {
                    pinchScale = 1f
                    playerView.scaleX = 1f
                    playerView.scaleY = 1f
                }
                showControls()
            }
        })
        playerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (!isPinching && event.pointerCount <= 1) showControls()
            }
            true
        }
        buildControls()
        setContentView(root)
        root.setOnClickListener { toggleControls() }
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !isPinching) {
                // Locked mode prevents background taps from hiding/showing the control panel.
                if (locked) return@setOnTouchListener true
                val now = System.currentTimeMillis()
                if (now - lastTap < 300) { toggleFit(); lastTap = 0L } else { lastTap = now; toggleControls() }
            }
            true
        }
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
                // MediaStore document/content URIs often have no file extension. Give
                // Media3 the provider MIME type explicitly so it does not mis-detect
                // large local videos as an unknown/unsupported media source.
                val providerMime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
                val fallbackMime = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.US)) {
                    "mp4", "m4v" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "mov" -> "video/quicktime"
                    "3gp" -> "video/3gpp"
                    "ts", "m2ts" -> "video/mp2t"
                    "avi" -> "video/avi"
                    else -> null
                }
                val mime = providerMime?.takeIf { it.startsWith("video/") } ?: fallbackMime
                val itemBuilder = MediaItem.Builder().setMediaId(uri.toString()).setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build())
                if (mime != null) itemBuilder.setMimeType(mime)
                val item = itemBuilder.build()
                c.setMediaItem(item)
                c.setPlaybackSpeed(speed)
                c.prepare()
                c.playWhenReady = true
                c.play()
                val l = object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("NiceMultimediaVideo", "Shared Media3 playback failed: ${error.errorCodeName}", error)
                        runOnUiThread { Toast.makeText(this@NativeVideoPlayerActivity, "Video playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show() }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playButton.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) }
                }
                listener = l; c.addListener(l)
                handler.post(progress); showControls()
            } catch (e: Exception) {
                android.util.Log.e("NiceMultimediaVideo", "Could not connect to shared MediaSession", e)
                Toast.makeText(this, "Could not open video player", Toast.LENGTH_LONG).show(); finish()
            }
        }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    }

    private fun buildControls() {
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 8)
            setBackgroundColor(0x99000000.toInt())
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply { text = videoTitle; setTextColor(Color.WHITE); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(title, LinearLayout.LayoutParams(0, 40, 1f))
        speedButton = compactTool("1×") { cycleSpeed() }
        top.addView(speedButton)
        shareButton = compactTool("Share") { shareCurrentVideo() }
        top.addView(shareButton)
        controls.addView(top)

        // All playback/action icons stay in one compact horizontal row.
        val middle = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; weightSum = 9f }
        fun addTool(label: String, action: () -> Unit) {
            val v = compactTool(label, action)
            middle.addView(v, LinearLayout.LayoutParams(0, 44, 1f))
        }
        fun addIcon(icon: Int, action: () -> Unit) {
            val v = iconButton(icon, action)
            v.layoutParams = LinearLayout.LayoutParams(0, 44, 1f)
            v.setPadding(2, 4, 2, 4)
            middle.addView(v)
        }
        addIcon(android.R.drawable.ic_media_previous) { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
        addTool("↶5") { controller?.let { it.seekTo((it.currentPosition - 5_000).coerceAtLeast(0)) } }
        playButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
            setPadding(4, 4, 4, 4); setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() }; showControls() }
        }
        middle.addView(playButton, LinearLayout.LayoutParams(0, 44, 1f))
        addTool("15↷") { controller?.let { it.seekTo((it.currentPosition + 15_000).coerceAtMost(it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) } }
        addIcon(android.R.drawable.ic_media_next) { controller?.let { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() else Toast.makeText(this, "No next video in playback queue", Toast.LENGTH_SHORT).show() } }
        fitButton = compactTool("Fit") { toggleFit() }; middle.addView(fitButton, LinearLayout.LayoutParams(0, 44, 1f))
        rotateButton = compactTool("↻") { toggleOrientation() }; middle.addView(rotateButton, LinearLayout.LayoutParams(0, 44, 1f))
        lockButton = compactTool("🔓") { locked = !locked; lockButton.text = if (locked) "🔒" else "🔓"; if (locked) { controls.visibility = View.VISIBLE; handler.removeCallbacks(hideControls) } else showControls() }
        middle.addView(lockButton, LinearLayout.LayoutParams(0, 44, 1f))
        addTool("Vol") { controller?.let { p -> volumeMuted = !volumeMuted; p.volume = if (volumeMuted) 0f else 1f } }
        controls.addView(middle)

        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        positionLabel = timeLabel("00:00"); seekRow.addView(positionLabel)
        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true; handler.removeCallbacks(hideControls) }
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) positionLabel.text = formatTime(value.toLong()) }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val target = sb.progress.toLong().coerceAtLeast(0L)
                    controller?.let { p ->
                        val duration = p.duration
                        p.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                    }
                    seeking = false
                    showControls()
                }
            })
        }
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, 34, 1f)); durationLabel = timeLabel("00:00"); seekRow.addView(durationLabel)
        controls.addView(seekRow)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controls.setOnClickListener { showControls() }
    }

    private fun compactTool(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
        setPadding(2, 4, 2, 4); minWidth = 0
        setOnClickListener { action(); showControls() }
    }

    private fun tool(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(12, 12, 12, 12); setOnClickListener { action(); showControls() }
    }
    private fun iconButton(icon: Int, action: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(0, 58, 1f); setPadding(10, 10, 10, 10)
        setOnClickListener { action(); showControls() }
    }
    private fun timeLabel(value: String) = TextView(this).apply { text = value; setTextColor(Color.WHITE); textSize = 12f; setPadding(5, 0, 5, 0) }
    private fun toggleControls() { if (controls.visibility == View.VISIBLE) { if (!locked) controls.visibility = View.GONE } else showControls() }
    private fun showControls() { controls.visibility = View.VISIBLE; handler.removeCallbacks(hideControls); if (!locked) handler.postDelayed(hideControls, 5000) }
    private fun shareCurrentVideo() {
        val uri = videoUri ?: run { Toast.makeText(this, "Video URI unavailable", Toast.LENGTH_SHORT).show(); return }
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, videoTitle, uri)
            }
            startActivity(Intent.createChooser(send, "Share video"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share video: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }
    private fun toggleFit() { fitMode = (fitMode + 1) % 3; playerView.resizeMode = when (fitMode) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }; fitButton.text = when (fitMode) { 1 -> "Zoom"; 2 -> "Fill"; else -> "Fit" }; showControls() }
    private fun toggleOrientation() { requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; showControls() }
    private fun cycleSpeed() { speed = when { speed < 0.75f -> 0.75f; speed < 1f -> 1f; speed < 1.25f -> 1.25f; speed < 1.5f -> 1.5f; speed < 2f -> 2f; else -> 0.5f }; controller?.setPlaybackSpeed(speed); speedButton.text = String.format(Locale.US, "%.2f×", speed).replace(".00", "").replace(".50", ".5"); showControls() }
    private fun snapshotQueue(p: MediaController) { if (queueSnapshotTaken) return; previousQueue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }; previousIndex = p.currentMediaItemIndex.coerceAtLeast(0); previousPosition = p.currentPosition.coerceAtLeast(0L); previousPlayWhenReady = p.playWhenReady; queueSnapshotTaken = true }
    private fun formatTime(ms: Long): String { val s = (ms.coerceAtLeast(0L) / 1000); return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s/3600, (s%3600)/60, s%60) else String.format(Locale.US, "%02d:%02d", s/60, s%60) }
    override fun onStop() { super.onStop() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); playerView.player = null
        val c = controller; listener?.let { c?.removeListener(it) }
        if (isFinishing && queueSnapshotTaken && c != null) try {
            if (previousQueue.isNotEmpty()) { c.setMediaItems(previousQueue, previousIndex.coerceIn(0, previousQueue.lastIndex), previousPosition); c.prepare(); if (previousPlayWhenReady) c.play() else c.pause() } else c.clearMediaItems()
        } catch (e: Exception) { android.util.Log.w("NiceMultimediaVideo", "Could not restore previous audio queue", e) }
        controllerFuture?.let { MediaController.releaseFuture(it) }; controllerFuture = null; controller = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); if (isFinishing) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; super.onDestroy()
    }
    companion object { const val EXTRA_URI = "com.meraj.mediaplayer.VIDEO_URI"; const val EXTRA_TITLE = "com.meraj.mediaplayer.VIDEO_TITLE" }
}
