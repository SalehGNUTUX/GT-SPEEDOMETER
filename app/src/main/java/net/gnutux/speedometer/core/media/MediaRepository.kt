package net.gnutux.speedometer.core.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.core.content.FileProvider
import java.io.File
import android.content.ContentResolver

data class MediaItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateMs: Long,
    /** موجود فقط على أندرويد 9 فما دون، حيث نكتب في مجلد التطبيق */
    val file: File? = null,
)

/**
 * حفظ التسجيلات واللقطات وقراءتها.
 *
 * على أندرويد 10 فما فوق نكتب في **مكتبة الوسائط** لا في مجلد التطبيق الخاص:
 * الملف يظهر عندها في معرض الصور وفي أي مدير ملفات. الحفظ في المجلد الخاص كان
 * يعمل، لكنّ المستخدم لا يجد ملفّه — وملفٌّ لا يُعثر عليه كأنه لم يُحفظ.
 */
class MediaRepository(private val context: Context) {

    private val videoDirLegacy: File get() = File(context.getExternalFilesDir(null), "videos").apply { mkdirs() }
    private val imageDirLegacy: File get() = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private val useMediaStore: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // ===== الكتابة =====

    /**
     * `prepareRecording` مثقَّل لكل نوع مخرجات ولا يقبل النوع المجرّد `OutputOptions`،
     * فالاختيار بين المكتبة والمجلد الخاص يقع هنا لا في المتصل.
     */
    fun prepareRecording(
        recorder: Recorder,
        name: String,
        durationLimitMs: Long? = null,
    ): PendingRecording =
        if (useMediaStore) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_VIDEO)
                put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_PATH)
            }
            val options = MediaStoreOutputOptions
                .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .apply { durationLimitMs?.let { setDurationLimitMillis(it) } }
                .build()
            recorder.prepareRecording(context, options)
        } else {
            val options = FileOutputOptions.Builder(File(videoDirLegacy, name))
                .apply { durationLimitMs?.let { setDurationLimitMillis(it) } }
                .build()
            recorder.prepareRecording(context, options)
        }

    /**
     * الاسم الذي استقرّ عليه الملفّ فعلًا.
     *
     * MediaStore يفضّ تصادم الأسماء بإضافة `(1)` من عنده، فالاسم الذي طلبناه ليس
     * دائمًا الاسم المحفوظ. عرضُ الاسم المطلوب في رسالة النجاح كان يُرسل المستخدم
     * يبحث في المعرض عن ملفٍّ بهذا الاسم لا وجود له. الاستعلام صفٌّ واحد بمفتاحه،
     * فكلفتُه لحظةَ إنهاء التسجيل مهملة.
     */
    fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return@runCatching uri.lastPathSegment
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun saveImage(bitmap: Bitmap, name: String): Uri? =
        if (useMediaStore) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_IMAGE)
                put(MediaStore.Images.Media.RELATIVE_PATH, IMAGE_PATH)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.also {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        } else {
            val file = File(imageDirLegacy, name)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            uriFor(file)
        }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    // ===== القراءة =====

    fun list(): List<MediaItem> =
        if (useMediaStore) listFromMediaStore() else listFromAppDirs()

    private fun listFromMediaStore(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        queryCollection(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.RELATIVE_PATH,
            VIDEO_PATH,
            isVideo = true,
            into = out,
        )
        queryCollection(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.RELATIVE_PATH,
            IMAGE_PATH,
            isVideo = false,
            into = out,
        )
        return out.sortedByDescending { it.dateMs }
    }

    private fun queryCollection(
        collection: Uri,
        pathColumn: String,
        path: String,
        isVideo: Boolean,
        into: MutableList<MediaItem>,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "$pathColumn LIKE ?",
                arrayOf("$path%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    into += MediaItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "",
                        isVideo = isVideo,
                        // DATE_ADDED بالثواني لا بالملّي ثانية
                        dateMs = cursor.getLong(dateCol) * 1000L,
                    )
                }
            }
        }
    }

    private fun listFromAppDirs(): List<MediaItem> {
        val videos = videoDirLegacy.listFiles().orEmpty().map { it to true }
        val images = imageDirLegacy.listFiles().orEmpty().map { it to false }
        return (videos + images)
            .filter { it.first.isFile && it.first.length() > 0 }
            .map { (file, isVideo) ->
                MediaItem(
                    uri = uriFor(file),
                    name = file.name,
                    isVideo = isVideo,
                    dateMs = file.lastModified(),
                    file = file,
                )
            }
            .sortedByDescending { it.dateMs }
    }

    fun delete(item: MediaItem): Boolean = runCatching {
        if (item.file != null) {
            item.file.delete()
        } else {
            context.contentResolver.delete(item.uri, null, null) > 0
        }
    }.getOrDefault(false)

    /**
     * حذفٌ جماعيّ يتجاوز الفاشل ويرجع عدد من حُذف فعلًا.
     *
     * الملفّات تُحذف واحدًا واحدًا لا بنداءٍ واحد إلى `delete` بشرط `IN (…)`: المستعمل
     * اختارهم جميعًا قصدًا، وملفٌّ يرفض الحذف — بطاقةُ ذاكرةٍ نُزعت، أو صفٌّ في
     * `MediaStore` يملكه تطبيقٌ آخر — لا يجوز أن يُبطل حذف الباقي. و`delete` ملفوفٌ
     * بـ`runCatching` سلفًا فلا يُسقط الجولة استثناءٌ من مزوّد المحتوى.
     */
    fun deleteAll(items: List<MediaItem>): Int = items.count { delete(it) }

    fun thumbnail(item: MediaItem, sizePx: Int = 320): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.file == null) {
            context.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
        } else {
            val path = item.file?.absolutePath ?: return@runCatching null
            if (item.isVideo) {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val sample = (bounds.outWidth / sizePx).coerceAtLeast(1)
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    private companion object {
        const val VIDEO_PATH = "Movies/GT-SPEEDOMETER"
        const val IMAGE_PATH = "Pictures/GT-SPEEDOMETER"
        const val MIME_VIDEO = "video/mp4"
        const val MIME_IMAGE = "image/jpeg"
    }
}
