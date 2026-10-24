package com.example.studiomobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.example.studiomobile.workspace.WallpaperSettings

/** The Wallpaper settings page and the wallpaper behind the code editor. MainActivity only calls [show] and [applyToEditor]. */
class WallpaperUi(private val host: MainActivity) {
    /** Decoded wallpaper, kept while its stamp is unchanged so reopening the editor does not decode it again. */
    private var bitmap: Bitmap? = null
    private var bitmapStamp = -1L

    /** The wallpaper is chosen in the gallery (images only), not in the file manager that OpenDocument shows. */
    private val pickFromGallery = host.registerForActivityResult(object : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(context: Context, input: Void?): Intent =
            Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK && intent != null) intent.data else null
    }) { uri: Uri? -> if (uri != null) importMedia(uri, false) }

    /** Live wallpaper: a video (at most 60 seconds) picked in the gallery. */
    private val pickVideoFromGallery = host.registerForActivityResult(object : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(context: Context, input: Void?): Intent =
            Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK && intent != null) intent.data else null
    }) { uri: Uri? -> if (uri != null) importMedia(uri, true) }

    private val pickVideoFromFiles = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importMedia(uri, true)
    }

    /** Used only when the phone has no gallery app that handles the request above. */
    private val pickFromFiles = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importMedia(uri, false)
    }

    fun show() {
        host.show(MainActivity.Screen.WALLPAPER, R.layout.screen_wallpaper, true, R.color.cosmic_white)
        host.click(R.id.wallpaper_back) { host.goBack() }
        host.click(R.id.wallpaper_choose) {
            try { pickFromGallery.launch(null) }
            catch (noGallery: ActivityNotFoundException) { pickFromFiles.launch(arrayOf("image/*")) }
        }
        host.click(R.id.wallpaper_choose_video) {
            try { pickVideoFromGallery.launch(null) }
            catch (noGallery: ActivityNotFoundException) { pickVideoFromFiles.launch(arrayOf("video/*")) }
        }
        host.click(R.id.wallpaper_remove) {
            val stored = WallpaperSettings(host)
            if (stored.isSet) {
                stored.clear()
                bitmap = null
                host.toast(host.getString(R.string.wallpaper_removed))
                refreshPage()
            }
        }
        host.screenView.findViewById<View>(R.id.wallpaper_preview_card).clipToOutline = true
        val settings = WallpaperSettings(host)
        val seek = host.screenView.findViewById<SeekBar>(R.id.wallpaper_dim_seek)
        seek.max = WallpaperSettings.MAX_DIM_PERCENT
        seek.progress = settings.dimPercent()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) settings.setDimPercent(progress)
                showDim(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { }
            override fun onStopTrackingTouch(bar: SeekBar) { }
        })
        refreshPage()
    }

    private fun showDim(percent: Int) {
        host.screenView.findViewById<TextView>(R.id.wallpaper_dim_label).text = host.getString(R.string.wallpaper_dim, percent)
        host.screenView.findViewById<View>(R.id.wallpaper_preview_shade).setBackgroundColor(WallpaperSettings.dimColor(percent))
    }

    /** Brings the wallpaper page in line with what is stored: preview, darkness slider and the Remove button. */
    private fun refreshPage() {
        if (host.screen != MainActivity.Screen.WALLPAPER) return
        val settings = WallpaperSettings(host)
        val set = settings.isSet
        val view = host.screenView
        view.findViewById<View>(R.id.wallpaper_preview_image).visibility = if (set) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.wallpaper_preview_shade).visibility = if (set) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.wallpaper_preview_empty).visibility = if (set) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.wallpaper_dim_seek).isEnabled = set
        val remove = view.findViewById<View>(R.id.wallpaper_remove)
        remove.isEnabled = set
        remove.alpha = if (set) 1f else 0.4f
        showDim(settings.dimPercent())
        view.findViewById<View>(R.id.wallpaper_video_status).visibility = if (settings.isVideo) View.VISIBLE else View.GONE
        if (!set) return
        withWallpaper { decoded ->
            if (host.screen == MainActivity.Screen.WALLPAPER) {
                host.screenView.findViewById<ImageView>(R.id.wallpaper_preview_image).setImageBitmap(decoded)
            }
        }
    }

    /** Saves the picked picture (or video when [video] is true) as the wallpaper; a video longer than 60 seconds is refused. */
    private fun importMedia(uri: Uri, video: Boolean) {
        host.io.execute {
            var message: Int? = null
            try {
                val settings = WallpaperSettings(host)
                if (video) settings.importVideoFrom(host.contentResolver, uri) else settings.importFrom(host.contentResolver, uri)
            } catch (e: WallpaperSettings.TooLongException) {
                message = R.string.wallpaper_video_too_long
            } catch (e: Exception) {
                message = R.string.wallpaper_failed
            } catch (e: OutOfMemoryError) {
                message = R.string.wallpaper_failed
            }
            val failure = message
            host.runOnUiThread {
                if (failure != null) {
                    host.toast(host.getString(failure))
                } else {
                    bitmap = null
                    host.toast(host.getString(if (video) R.string.wallpaper_video_saved else R.string.wallpaper_saved))
                    refreshPage()
                }
            }
        }
    }

    /** Hands over the decoded wallpaper on the UI thread: from the cache when it is current, otherwise after decoding it in the background. */
    private fun withWallpaper(ready: (Bitmap) -> Unit) {
        val settings = WallpaperSettings(host)
        val stamp = settings.stamp()
        val cached = bitmap
        if (cached != null && bitmapStamp == stamp) { ready(cached); return }
        host.io.execute {
            val decoded = settings.load()
            host.runOnUiThread {
                if (decoded != null) {
                    bitmap = decoded
                    bitmapStamp = stamp
                    ready(decoded)
                }
            }
        }
    }

    /** Shows the user's wallpaper (with its darkness) behind the code area, or nothing when none is set. */
    fun applyToEditor() {
        val image = host.screenView.findViewById<ImageView>(R.id.editor_wallpaper)
        val shade = host.screenView.findViewById<View>(R.id.editor_wallpaper_shade)
        if (image == null || shade == null) return
        val settings = WallpaperSettings(host)
        val set = settings.isSet
        image.visibility = if (set) View.VISIBLE else View.GONE
        shade.visibility = if (set) View.VISIBLE else View.GONE
        if (!set) { image.setImageDrawable(null); return }
        shade.setBackgroundColor(WallpaperSettings.dimColor(settings.dimPercent()))
        if (settings.isVideo) attachVideo(image, settings)
        withWallpaper { decoded ->
            if (host.screen == MainActivity.Screen.EDITOR) {
                host.screenView.findViewById<ImageView>(R.id.editor_wallpaper)?.setImageBitmap(decoded)
            }
        }
    }

    // ------------------------------------------------------------------ live video wallpaper
    //
    // Lazy: nothing is created until the code editor is on screen and a video wallpaper is set. The player lives only while the editor is
    // visible: MainActivity calls [releaseVideo] on every screen change (home, preview, build, ...), [pauseVideo] when the app goes to the
    // background and [resumeVideo] when it comes back, which continues from where it was paused. The first frame (the stored picture) is
    // shown underneath until the video renders.

    /** True while the app is in the foreground; the video only plays then. */
    private var foreground = false
    private var textureView: TextureView? = null
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var resumeAt = 0
    /** True while a question is on screen (leaving the editor): the player stays released until the answer is "no". */
    private var suspended = false

    private fun attachVideo(poster: ImageView, settings: WallpaperSettings) {
        val parent = poster.parent as? FrameLayout ?: return
        val tv = TextureView(host)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { startVideo(settings) }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { player?.let { fitVideo(tv, it) } }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { stopPlayer(true); return true }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { }
        }
        // Above the picture, below the dark shade, so the darkness setting applies to the video too.
        parent.addView(tv, parent.indexOfChild(poster) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        textureView = tv
        resumeAt = 0
        startVideo(settings)
    }

    private fun startVideo(settings: WallpaperSettings) {
        val tv = textureView ?: return
        if (!foreground || suspended || player != null || host.screen != MainActivity.Screen.EDITOR) return
        val texture = tv.surfaceTexture
        if (!tv.isAvailable || texture == null || !settings.isVideo) return
        val surface = Surface(texture)
        val mp = MediaPlayer()
        try {
            mp.setDataSource(settings.videoFile().absolutePath)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)   // a wallpaper is silent and never takes audio focus from the user's music
            mp.setOnPreparedListener { p ->
                if (player === p) {
                    fitVideo(tv, p)
                    if (resumeAt > 0) p.seekTo(resumeAt)
                    if (foreground) p.start()
                }
            }
            mp.setOnErrorListener { p, _, _ -> if (player === p) stopPlayer(false); true }   // the picture underneath stays visible
            player = mp
            playerSurface = surface
            mp.prepareAsync()
        } catch (e: Exception) {
            try { mp.release() } catch (ignored: Exception) { }
            surface.release()
            player = null
            playerSurface = null
        }
    }

    /** Scales the video to fit inside the area, centered, so nothing is cut off (like the picture's fitCenter). The rest of the area keeps the editor's background. */
    private fun fitVideo(tv: TextureView, p: MediaPlayer) {
        val videoW = p.videoWidth.toFloat()
        val videoH = p.videoHeight.toFloat()
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (videoW <= 0f || videoH <= 0f || w <= 0f || h <= 0f) return
        val scale = minOf(w / videoW, h / videoH)
        val matrix = Matrix()
        matrix.setScale(videoW * scale / w, videoH * scale / h, w / 2f, h / 2f)
        tv.setTransform(matrix)
    }

    private fun stopPlayer(keepPosition: Boolean) {
        val p = player ?: return
        player = null
        if (keepPosition) resumeAt = try { p.currentPosition } catch (e: Exception) { 0 }
        try { p.setOnPreparedListener(null); p.setOnErrorListener(null) } catch (ignored: Exception) { }
        try { p.release() } catch (ignored: Exception) { }
        playerSurface?.release()
        playerSurface = null
    }

    /**
     * Stops the player right now and keeps it stopped, remembering the position. Used the moment the "Are you sure you want to exit?" dialog
     * appears, so the video's decoder and surface are already freed before the screen change and the exit does not stutter.
     */
    fun suspendVideo() {
        suspended = true
        stopPlayer(true)
    }

    /** The answer was "no": play again from where it stopped. */
    fun unsuspendVideo() {
        suspended = false
        if (host.screen == MainActivity.Screen.EDITOR) startVideo(WallpaperSettings(host))
    }

    /** The app went to the background: stop playing but remember the position. */
    fun pauseVideo() {
        foreground = false
        stopPlayer(true)
    }

    /** The app is back: continue the video from where it was paused, only when the code editor is the open screen. */
    fun resumeVideo() {
        foreground = true
        if (host.screen == MainActivity.Screen.EDITOR) startVideo(WallpaperSettings(host))
    }

    /** The editor is left (home, build, preview, ...) or closed: stop and free the player; the next visit to the editor starts from the beginning. */
    fun releaseVideo() {
        suspended = false
        stopPlayer(false)
        textureView = null
        resumeAt = 0
    }
}
