package app.snapfade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore.Images.Media
import android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
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

    fun share(ctx: Context, f: File) {
        val uri = uri(ctx, f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
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
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "image/png"
        val values = ContentValues().apply {
            put(Media.DISPLAY_NAME, f.name)
            put(Media.MIME_TYPE, mime)
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
