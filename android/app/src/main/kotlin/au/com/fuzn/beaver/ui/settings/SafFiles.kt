package au.com.fuzn.beaver.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reading and writing the files the system file picker hands back (Storage Access Framework Uris). */
object SafFiles {
    /** Replaces the contents of [uri] with [text] (UTF-8). */
    suspend fun write(resolver: ContentResolver, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("The file could not be opened for writing.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun read(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw java.io.IOException("The file could not be opened.")
        stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** The file's display name, or "the chosen file" when the provider does not say. */
    fun displayName(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "the chosen file"
}
