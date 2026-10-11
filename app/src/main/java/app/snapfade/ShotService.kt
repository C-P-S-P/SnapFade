package app.snapfade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore.Images.Media
import android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.io.File

/**
 * Foreground service that watches MediaStore: when the system screenshot lands, copies it to
 * the app cache, puts it on the clipboard, deletes it from the gallery, shows the Save popup.
 */
class ShotService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var lastId = -1L
    private var savedName: String? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val images: Uri = Media.getContentUri(VOLUME_EXTERNAL_PRIMARY)
    private val dismissRunnable = Runnable { removeOverlay() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = onMediaChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        contentResolver.registerContentObserver(images, true, observer)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        contentResolver.unregisterContentObserver(observer)
        removeOverlay()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

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
        if (!Settings.canDrawOverlays(this)) return toast(getString(R.string.copied))

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_shot, null)
        view.findViewById<View>(R.id.save).setOnClickListener {
            saveToGallery(file)
            toast(getString(R.string.saved))
            removeOverlay()
        }
        view.findViewById<View>(R.id.share).setOnClickListener {
            Shots.share(this, file)
            removeOverlay()
        }
        view.findViewById<View>(R.id.dismiss).setOnClickListener { removeOverlay() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // bottom-right: OEM screenshot previews (vivo, Pixel, Samsung) sit bottom-left
            gravity = Gravity.BOTTOM or Gravity.END
            val d = resources.displayMetrics.density
            x = (OVERLAY_END_DP * d).toInt()
            y = (OVERLAY_BOTTOM_DP * d).toInt()
        }
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
        const val OVERLAY_BOTTOM_DP = 110
        const val OVERLAY_END_DP = 16
        private const val CHANNEL = "service"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ShotService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ShotService::class.java))
    }
}
