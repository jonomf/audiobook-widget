package com.jono.audiobookwidget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves the saved cover images to the launcher by URI. That way covers don't travel
 * inside every widget update (which has a size limit), even with five layouts.
 *
 * URI shape: content://com.jono.audiobookwidget.art/<package>/<version>
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val pkg = uri.pathSegments.firstOrNull()
        val ctx = context
        if (ctx == null || !Sources.isKnown(pkg)) throw FileNotFoundException(uri.toString())
        val file = Store.coverFile(ctx, pkg!!)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0
}
