package chat.homebuddy.core.data.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Files picked, or shared from another app, read through their content:// address. Main-safe. */
interface PickedFileDataSource {
    /** The name the other app gives the file, or null when it gives none. */
    suspend fun displayName(uri: String): String?

    /** Opens the file. Throws [IOException] when it can't be read, for example when permission to read it has lapsed. */
    suspend fun open(uri: String): InputStream
}

class ContentResolverPickedFileDataSource(private val context: Context) : PickedFileDataSource {
    override suspend fun displayName(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun open(uri: String): InputStream = withContext(Dispatchers.IO) {
        val stream = try {
            context.contentResolver.openInputStream(Uri.parse(uri))
        } catch (error: SecurityException) {
            throw IOException("No permission to read the file.", error)
        }
        stream ?: throw IOException("Can't open the file.")
    }
}
