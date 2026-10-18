package com.zohan.nghekinhthanh1925

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Shares only the verified APK in private cache, never Bible/audio files. */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        fileFor(uri)
        return APK_MIME
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Update APK is read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "NgheKinhThanh-update.apk"
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    private fun fileFor(uri: Uri): File {
        val context = context ?: throw FileNotFoundException("Provider unavailable")
        if (uri != UpdateApkProvider.uri(context)) throw FileNotFoundException("Unknown update URI")
        return File(context.cacheDir, "app-updates/update.apk")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw SecurityException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read-only provider")

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.updates/latest.apk")
    }
}
