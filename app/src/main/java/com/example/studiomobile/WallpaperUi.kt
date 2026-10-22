package com.example.studiomobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.View
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
    }) { uri: Uri? -> if (uri != null) importImage(uri) }

    /** Used only when the phone has no gallery app that handles the request above. */
    private val pickFromFiles = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importImage(uri)
    }

    fun show() {
        host.show(MainActivity.Screen.WALLPAPER, R.layout.screen_wallpaper, true, R.color.cosmic_white)
        host.click(R.id.wallpaper_back) { host.goBack() }
        host.click(R.id.wallpaper_choose) {
            try { pickFromGallery.launch(null) }
            catch (noGallery: ActivityNotFoundException) { pickFromFiles.launch(arrayOf("image/*")) }
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
        if (!set) return
        withWallpaper { decoded ->
            if (host.screen == MainActivity.Screen.WALLPAPER) {
                host.screenView.findViewById<ImageView>(R.id.wallpaper_preview_image).setImageBitmap(decoded)
            }
        }
    }

    private fun importImage(uri: Uri) {
        host.io.execute {
            var saved: Boolean
            try {
                WallpaperSettings(host).importFrom(host.contentResolver, uri)
                saved = true
            } catch (e: Exception) {
                saved = false
            } catch (e: OutOfMemoryError) {
                saved = false
            }
            val ok = saved
            host.runOnUiThread {
                if (!ok) {
                    host.toast(host.getString(R.string.wallpaper_failed))
                } else {
                    bitmap = null
                    host.toast(host.getString(R.string.wallpaper_saved))
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
        withWallpaper { decoded ->
            if (host.screen == MainActivity.Screen.EDITOR) {
                host.screenView.findViewById<ImageView>(R.id.editor_wallpaper)?.setImageBitmap(decoded)
            }
        }
    }
}
