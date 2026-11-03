package app.snapfade

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

object Shots {
    const val CLIP_LABEL = "SnapFade"
    const val KEY_PATH = "path"
    private const val PREFS = "snapfade"
    private const val KEY_TTL = "ttl_hours"
    private const val KEY_ENABLED = "enabled"
    const val DEFAULT_TTL_HOURS = 6
    val EXPIRY_OPTIONS = intArrayOf(1, 3, 6, 12, 24)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ttlMs(ctx: Context): Long = TimeUnit.HOURS.toMillis(getTtlHours(ctx).toLong())

    fun getTtlHours(ctx: Context): Int = prefs(ctx).getInt(KEY_TTL, DEFAULT_TTL_HOURS)

    fun setTtlHours(ctx: Context, hours: Int) = prefs(ctx).edit().putInt(KEY_TTL, hours).apply()

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    /** Android 11 has no MANAGE_MEDIA, so there All files access is still the only dialog-free delete. */
    val legacyStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    val readPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(readPermission) == PackageManager.PERMISSION_GRANTED

    fun canManageMedia(ctx: Context): Boolean = !legacyStorage && MediaStore.canManageMedia(ctx)

    /**
     * Everything needed to see a new screenshot and delete it from the gallery without a prompt.
     * On Android 12+ the delete goes through an Activity started from the background, which the
     * system only allows while the app holds the overlay permission; without it the shot would
     * stay in the gallery, so the overlay step is required there rather than optional.
     */
    fun hasMediaAccess(ctx: Context): Boolean =
        if (legacyStorage) Environment.isExternalStorageManager()
        else canRead(ctx) && canManageMedia(ctx) && Settings.canDrawOverlays(ctx)

    /**
     * MANAGE_MEDIA does not allow a plain delete(); it only drops the confirmation dialog from
     * createDeleteRequest, which must still be launched for a result from an Activity.
     */
    fun deleteFromGallery(ctx: Context, uri: Uri) {
        if (legacyStorage) ctx.contentResolver.delete(uri, null, null) else DeleteActivity.start(ctx, uri)
    }

    fun dir(ctx: Context): File = File(ctx.cacheDir, "shots").apply { mkdirs() }

    fun all(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun msLeft(ctx: Context, f: File): Long = f.lastModified() + ttlMs(ctx) - System.currentTimeMillis()

    fun sweep(ctx: Context) = all(ctx).filter { msLeft(ctx, it) <= 0 }.forEach { it.delete() }

    fun timeLeft(ctx: Context, f: File): String {
        val m = (msLeft(ctx, f) / 60_000).coerceAtLeast(0)
        return ctx.getString(R.string.time_left, m / 60, m % 60)
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun remove(ctx: Context, f: File) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        if (cm.primaryClip?.getItemAt(0)?.uri == uri(ctx, f)) cm.clearPrimaryClip()
        f.delete()
        WorkManager.getInstance(ctx).cancelUniqueWork(f.name)
    }

    fun copyToClipboard(ctx: Context, f: File) {
        val uri = uri(ctx, f)
        for (p in listOf("com.android.systemui", "android")) {
            runCatching { ctx.grantUriPermission(p, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newUri(ctx.contentResolver, CLIP_LABEL, uri))
    }

    /** Android 13+ shows its own "Copied" chip on setPrimaryClip; only toast below that. */
    fun toastCopied(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "image/png"

    fun share(ctx: Context, f: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime(f)
            putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Opens the shot in an image editor (crop, markup). The gallery copy is gone by now, so the
     * OEM screenshot preview's own Edit button has nothing to open; this is the replacement.
     * Read-only grant on purpose: with write access Pixel Markup truncates the file on open
     * (0 bytes, blank canvas). Editors save their result as a new gallery file instead; if it
     * lands in the Screenshots folder the observer picks it up as a fresh temporary shot.
     */
    fun edit(ctx: Context, f: File) {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri(ctx, f), mime(f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun scheduleExpiry(ctx: Context, f: File) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            f.name, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ExpireWorker>()
                .setInitialDelay(ttlMs(ctx), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_PATH to f.path))
                .build()
        )
    }

    fun saveToGallery(ctx: Context, f: File) {
        val values = ContentValues().apply {
            put(Media.DISPLAY_NAME, f.name)
            put(Media.MIME_TYPE, mime(f))
            put(Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots")
            put(Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(Media.getContentUri(VOLUME_EXTERNAL_PRIMARY), values) ?: return
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
