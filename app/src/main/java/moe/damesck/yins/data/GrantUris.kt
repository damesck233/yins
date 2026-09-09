package moe.damesck.yins.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import moe.damesck.yins.YLog

/**
 * MediaProvider's media_grants only accept photo-picker style URIs, but the grant itself is
 * keyed by the MediaStore file id, which every indexed file (documents included) has. This turns
 * whatever a picker handed us into that format.
 */
object GrantUris {
    private const val LOCAL_PICKER_AUTHORITY = "com.android.providers.media.photopicker"
    private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

    data class Result(val pickerUris: List<Uri>, val unresolved: Int)

    fun toPickerUris(context: Context, uris: Collection<Uri>): Result {
        val out = ArrayList<Uri>(uris.size)
        var unresolved = 0
        for (uri in uris) {
            val id = resolveFileId(context, uri)
            if (id == null) {
                unresolved++
                YLog.w("cannot map $uri to a MediaStore id")
            } else {
                out += pickerUri(id)
            }
        }
        return Result(out, unresolved)
    }

    private fun pickerUri(id: Long): Uri =
        Uri.parse("content://${MediaStore.AUTHORITY}/picker/${Process.myUid() / 100_000}/$LOCAL_PICKER_AUTHORITY/media/$id")

    private fun resolveFileId(context: Context, uri: Uri): Long? {
        // Already a picker URI (from ACTION_PICK_IMAGES): keep the id as is.
        if (uri.authority == MediaStore.AUTHORITY) {
            return runCatching { ContentUris.parseId(uri) }.getOrNull()
        }
        // DocumentsUI "Images"/"Videos"/"Audio" roots: docId is "image:123" etc.
        if (uri.authority == MEDIA_DOCUMENTS_AUTHORITY) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            return docId.substringAfter(':', "").toLongOrNull()
        }
        // External storage documents: let MediaProvider map the path to its row.
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val media = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            if (media != null) return runCatching { ContentUris.parseId(media) }.getOrNull()
        }
        return null
    }
}
