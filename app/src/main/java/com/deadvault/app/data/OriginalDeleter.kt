package com.deadvault.app.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Removes the original photo/video from the phone once it is safely inside the vault.
 *
 * Android only lets an app delete other apps' media silently when it holds "All files access"
 * (MANAGE_EXTERNAL_STORAGE). Without it callers fall back to the system confirmation dialog.
 */
object OriginalDeleter {

    fun canDeleteSilently(): Boolean = Environment.isExternalStorageManager()

    /** True only if the original is really gone afterwards. */
    fun delete(context: Context, uri: Uri): Boolean {
        if (!canDeleteSilently()) return false
        val resolver = context.contentResolver

        val media = toMediaStoreUri(uri)
        if (media != null) {
            @Suppress("DEPRECATION")
            val path = runCatching {
                resolver.query(media, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()

            val rows = runCatching { resolver.delete(media, null, null) }.getOrDefault(0)
            if (rows > 0) return true

            // The database row could not be removed directly: delete the file and refresh the index.
            if (path != null) return deleteFile(context, File(path))
            return false
        }

        val file = externalStorageFile(uri) ?: return false
        return deleteFile(context, file)
    }

    /** Only reports success when a file really existed and is now gone (never for a guessed path). */
    private fun deleteFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val ok = file.delete()
        if (ok) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return ok
    }

    /** content://com.android.providers.media.documents/... (the Images / Videos browser) -> MediaStore uri. */
    fun toMediaStoreUri(uri: Uri): Uri? {
        if (uri.authority != "com.android.providers.media.documents") return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val base = when (parts[0]) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        return ContentUris.withAppendedId(base, id)
    }

    /** content://com.android.externalstorage.documents/... (browsing a folder) -> real file. */
    private fun externalStorageFile(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2 || parts[1].isEmpty()) return null
        val root = if (parts[0] == "primary") {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/${parts[0]}")
        }
        return File(root, parts[1])
    }
}
