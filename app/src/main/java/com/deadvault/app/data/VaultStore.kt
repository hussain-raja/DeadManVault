package com.deadvault.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")

    fun toJson(): String = JSONObject()
        .put("id", id).put("name", name).put("mime", mime)
        .put("size", size).put("addedAt", addedAt)
        .toString()

    companion object {
        fun fromJson(s: String): VaultItem {
            val o = JSONObject(s)
            return VaultItem(
                o.getString("id"), o.getString("name"), o.getString("mime"),
                o.getLong("size"), o.getLong("addedAt"),
            )
        }
    }
}

/**
 * Everything is stored inside the app's private storage (never in MediaStore, so no gallery
 * app can see it) and each file is AES-256-GCM encrypted with a key held by the Android Keystore.
 *
 * Per item there are three encrypted files: <id>.dat (the media), <id>.thumb (JPEG thumbnail)
 * and <id>.meta (name / mime / size). The id is random, so file names reveal nothing.
 */
class VaultStore(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    // A getter, not a field: the folder is deleted by a wipe and must be recreated for a new vault.
    private val dir: File get() = File(app.filesDir, DIR_NAME).also { it.mkdirs() }
    private val playDir = File(app.cacheDir, PLAY_DIR)

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private fun enc(file: File): EncryptedFile =
        EncryptedFile.Builder(
            app, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    private fun writeEncrypted(file: File, bytes: ByteArray) {
        if (file.exists()) file.delete() // EncryptedFile refuses to overwrite
        enc(file).openFileOutput().use { it.write(bytes) }
    }

    private fun readEncrypted(file: File): ByteArray =
        enc(file).openFileInput().use { it.readBytes() }

    // ---------------------------------------------------------------- listing

    fun listItems(): List<VaultItem> =
        (dir.listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
            .mapNotNull { f ->
                runCatching { VaultItem.fromJson(String(readEncrypted(f))) }.getOrNull()
            }
            .sortedByDescending { it.addedAt }

    /** Removes leftovers of imports that were interrupted before their .meta was written. */
    fun purgeOrphans() {
        val files = dir.listFiles() ?: return
        val known = files.filter { it.name.endsWith(".meta") }.map { it.name.removeSuffix(".meta") }.toSet()
        files.filter { it.name.substringBefore('.') !in known }.forEach { it.delete() }
    }

    // ----------------------------------------------------------------- import

    /** Copies [uri] into the vault, encrypted. Returns null if anything went wrong. */
    fun import(uri: Uri): VaultItem? {
        val mime = resolver.getType(uri) ?: return null
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null

        val id = UUID.randomUUID().toString()
        val dataFile = File(dir, "$id.dat")
        return try {
            val (name, _) = queryNameAndSize(uri, mime)
            var total = 0L
            resolver.openInputStream(uri)!!.use { input ->
                enc(dataFile).openFileOutput().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
            makeThumbnail(uri, mime)?.let { writeEncrypted(File(dir, "$id.thumb"), it) }
            val item = VaultItem(id, name, mime, total, System.currentTimeMillis())
            writeEncrypted(File(dir, "$id.meta"), item.toJson().toByteArray())
            item
        } catch (e: Exception) {
            deleteFiles(id)
            null
        }
    }

    private fun queryNameAndSize(uri: Uri, mime: String): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1L else c.getLong(1)
            }
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return (name ?: "item_${System.currentTimeMillis()}.$ext") to size
    }

    // ------------------------------------------------------------- thumbnails

    private fun makeThumbnail(uri: Uri, mime: String): ByteArray? {
        val bmp: Bitmap = try {
            if (mime.startsWith("video/")) videoFrame(uri) else decodeImage(ImageDecoder.createSource(resolver, uri), THUMB_EDGE)
        } catch (e: Exception) {
            null
        } ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0)
            frame?.let { scaleDown(it, THUMB_EDGE) }
        } finally {
            r.release()
        }
    }

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val f = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    }

    /** ImageDecoder applies EXIF rotation for us and can down-sample while decoding. */
    private fun decodeImage(source: ImageDecoder.Source, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxEdge) decoder.setTargetSampleSize(longest / maxEdge)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    fun thumbnail(id: String): Bitmap? = runCatching {
        val f = File(dir, "$id.thumb")
        if (!f.exists()) return null
        val bytes = readEncrypted(f)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    // ---------------------------------------------------------------- viewing

    /** Decrypts an image fully in memory (nothing is written to disk). */
    fun image(id: String, maxEdge: Int = FULL_EDGE): Bitmap? = runCatching {
        val bytes = readEncrypted(File(dir, "$id.dat"))
        decodeImage(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxEdge)
    }.getOrNull()

    /**
     * Videos need a real file for the platform player, so a decrypted copy is placed in the app's
     * private cache for the duration of playback. It is deleted when playback ends, when the
     * vault locks and at every app start.
     */
    fun decryptForPlayback(item: VaultItem): File? = runCatching {
        playDir.mkdirs()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime) ?: "mp4"
        // Unique per call so a quick replay can't collide with the previous copy being deleted.
        val out = File(playDir, "${item.id}-${System.nanoTime()}.$ext")
        if (out.exists()) out.delete()
        enc(File(dir, "${item.id}.dat")).openFileInput().use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out
    }.getOrNull()

    fun deletePlayback(file: File) {
        runCatching { file.delete() }
    }

    fun clearPlayback() {
        runCatching { playDir.deleteRecursively() }
    }

    // ------------------------------------------------------- delete / restore

    fun delete(id: String) = deleteFiles(id)

    private fun deleteFiles(id: String) {
        listOf("dat", "thumb", "meta").forEach { File(dir, "$id.$it").delete() }
    }

    /** Copies the item back into the public gallery (Pictures/ or Movies/ "DeadVault Restored"). */
    fun exportToGallery(item: VaultItem): Boolean {
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val root = if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/DeadVault Restored")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(target)!!.use { out ->
                enc(File(dir, "${item.id}.dat")).openFileInput().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            false
        }
    }

    companion object {
        const val DIR_NAME = "vault"
        const val PLAY_DIR = "play"
        private const val THUMB_EDGE = 384
        private const val FULL_EDGE = 2048
    }
}
