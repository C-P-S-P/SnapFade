package app.tempss

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Images.Media
import android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.Toast
import java.io.File

/**
 * Lives as an accessibility service only to stay resident and to draw an overlay.
 * Watches MediaStore: when the system screenshot (Power+VolDown) lands, copies it to
 * the app cache, puts it on the clipboard, deletes it from the gallery, shows Save popup.
 */
class ShotService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var lastId = -1L
    private var savedName: String? = null
    private lateinit var wm: WindowManager
    private val images: Uri = Media.getContentUri(VOLUME_EXTERNAL_PRIMARY)
    private val dismissRunnable = Runnable { removeOverlay() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = onMediaChanged()
    }

    override fun onServiceConnected() {
        // Must be fetched after connect: AccessibilityService attaches its overlay token on first lookup.
        wm = getSystemService(WindowManager::class.java)
        contentResolver.registerContentObserver(images, true, observer)
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        removeOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    private fun onMediaChanged() {
        if (!Environment.isExternalStorageManager()) return // need All files access to delete
        val since = System.currentTimeMillis() / 1000 - 20
        contentResolver.query(
            images,
            arrayOf(Media._ID, Media.DISPLAY_NAME, Media.OWNER_PACKAGE_NAME),
            "${Media.DATE_ADDED} >= ? AND (${Media.RELATIVE_PATH} LIKE '%Screenshot%' OR ${Media.DISPLAY_NAME} LIKE 'Screenshot%')",
            arrayOf(since.toString()),
            "${Media.DATE_ADDED} DESC, ${Media._ID} DESC"
        )?.use { c ->
            if (!c.moveToFirst()) return
            val id = c.getLong(0)
            val name = c.getString(1)
            if (id == lastId || c.getString(2) == packageName || name == savedName) return // seen, or our own Save
            lastId = id
            grab(ContentUris.withAppendedId(images, id), name)
        }
    }

    private fun grab(src: Uri, name: String) {
        Shots.sweep(this)
        val file = File(Shots.dir(this), name)
        contentResolver.openInputStream(src)?.use { i -> file.outputStream().use { i.copyTo(it) } } ?: return
        contentResolver.delete(src, null, null)
        Shots.copyToClipboard(this, file)
        Shots.scheduleExpiry(this, file)
        showOverlay(file)
    }

    private fun showOverlay(file: File) {
        removeOverlay()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_shot, null)
        val thumb = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
        view.findViewById<ImageView>(R.id.thumb).setImageBitmap(thumb)
        view.findViewById<View>(R.id.save).setOnClickListener {
            saveToGallery(file)
            toast(getString(R.string.saved))
            removeOverlay()
        }
        view.findViewById<View>(R.id.dismiss).setOnClickListener { removeOverlay() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // no SYSTEM_ALERT_WINDOW needed
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // top: clear of OEM screenshot previews
            y = 200
        }
        // ponytail: after a quick disable/enable of the service the overlay token can be null;
        // don't take the whole process down, the shot is still in the in-app list.
        if (runCatching { wm.addView(view, lp) }.isFailure) return toast(getString(R.string.copied))
        overlay = view
        handler.postDelayed(dismissRunnable, OVERLAY_MS)
    }

    private fun removeOverlay() {
        handler.removeCallbacks(dismissRunnable)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    /** Owner is us, so the observer skips it; savedName is a belt-and-braces guard. */
    private fun saveToGallery(file: File) {
        savedName = file.name
        Shots.saveToGallery(this, file)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val OVERLAY_MS = 6_000L
    }
}
