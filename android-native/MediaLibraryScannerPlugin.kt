package com.meraj.mediaplayer

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/** Queries Android MediaStore for shared audio/video files after runtime permission is granted. */
@CapacitorPlugin(name = "MediaLibraryScanner")
class MediaLibraryScannerPlugin : Plugin() {
    @PluginMethod
    fun scan(call: PluginCall) {
        val type = call.getString("type", "audio") ?: "audio"
        val resolver = context.contentResolver
        val items = JSArray()
        val collection: Uri = when (type) {
            "video" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = if (type == "video") mutableListOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        ) else mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        val sort = if (type == "video") "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
                   else "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
        try {
            resolver.query(collection, projection.toTypedArray(), null, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val item = JSObject()
                    item.put("id", id.toString())
                    item.put("uri", uri.toString())
                    item.put("name", cursor.getString(nameCol) ?: "Unknown media")
                    item.put("size", cursor.getLong(sizeCol))
                    item.put("type", cursor.getString(mimeCol) ?: "")
                    item.put("duration", if (durationCol >= 0) cursor.getLong(durationCol) else 0L)
                    item.put("artist", if (artistCol >= 0) cursor.getString(artistCol) ?: "Unknown artist" else "Unknown artist")
                    item.put("album", if (albumCol >= 0) cursor.getString(albumCol) ?: "Unknown album" else "Unknown album")
                    item.put("relativePath", if (pathCol >= 0) cursor.getString(pathCol) ?: "Device files" else "Device files")
                    items.put(item)
                }
            }
            call.resolve(JSObject().put("items", items).put("count", items.length()).put("type", type))
        } catch (e: SecurityException) {
            call.reject("Media permission denied", e)
        } catch (e: Exception) {
            call.reject("Could not scan device media: ${e.message}", e)
        }
    }
}
